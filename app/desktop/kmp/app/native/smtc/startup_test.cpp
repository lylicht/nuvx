void complete_activation_before_result();
#define VYRAL_SMTC_TEST_BEFORE_START_RESULT complete_activation_before_result
#include "startup.h"

#undef NDEBUG
#include <cassert>
#include <iostream>

namespace {
std::thread worker;
std::atomic<bool> running{false};
std::atomic<bool> finished{true};
std::atomic<bool> stop_requested{false};
std::atomic<bool> release_activation{false};
bool finish_at_result = false;

void fail_activation() { finished = true; }

void delayed_failure() {
    while (!release_activation.load() && !stop_requested.load()) std::this_thread::yield();
    finished = true;
}

void delayed_activation() {
    while (!release_activation.load() && !stop_requested.load()) std::this_thread::yield();
    if (!stop_requested.load()) {
        running = true;
        while (!stop_requested.load()) std::this_thread::yield();
    }
    running = false;
    finished = true;
}

void stop() {
    stop_requested = true;
    if (worker.joinable()) worker.join();
    assert(!worker.joinable());
}
} // namespace

// Force completion after the join decision instead of relying on scheduler timing.
void complete_activation_before_result() {
    if (!finish_at_result) return;
    release_activation = true;
    while (!finished.load()) std::this_thread::yield();
}

int main() {
    assert(!start_media_thread(worker, running, finished, stop_requested, fail_activation, 400));
    assert(!worker.joinable());

    // A pending activation remains owned even after the startup wait expires.
    assert(start_media_thread(worker, running, finished, stop_requested, delayed_activation, 0));
    assert(worker.joinable());
    stop();
    assert(!running.load());
    stop();

    assert(start_media_thread(worker, running, finished, stop_requested, delayed_failure, 0));
    release_activation = true;
    while (!finished.load()) std::this_thread::yield();
    assert(worker.joinable());
    stop();
    release_activation = false;

    finish_at_result = true;
    const bool raced_ownership = start_media_thread(worker, running, finished, stop_requested, delayed_failure, 0);
    const bool raced_joinable = worker.joinable();
    stop();
    assert(raced_ownership == raced_joinable);
    finish_at_result = false;
    release_activation = false;

    // Restart after cancellation, then complete activation and stop normally.
    assert(start_media_thread(worker, running, finished, stop_requested, delayed_activation, 0));
    release_activation = true;
    while (!running.load()) std::this_thread::yield();
    assert(start_media_thread(worker, running, finished, stop_requested, delayed_activation, 0));
    stop();

    // Completion can race with the final ownership result after the polling loop.
    for (int i = 0; i < 2000; ++i) {
        const bool owned = start_media_thread(worker, running, finished, stop_requested, fail_activation, 0);
        const bool joinable = worker.joinable();
        if (joinable) worker.join();
        if (owned != joinable) {
            std::cerr << "Lost native thread ownership at iteration " << i << '\n';
            return 1;
        }
    }
    std::cout << "SMTC startup ownership checks passed\n";
}
