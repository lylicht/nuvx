#pragma once

#include <atomic>
#include <chrono>
#include <thread>

// Start/stop callers are serialized by DesktopWindowsMedia's monitor.
inline bool start_media_thread(
    std::thread& thread,
    std::atomic<bool>& running,
    std::atomic<bool>& finished,
    std::atomic<bool>& stop_requested,
    void (*pump)(),
    int wait_steps) {
    if (thread.joinable()) {
        if (!finished.load()) return true;
        thread.join();
    }
    running = false;
    finished = false;
    stop_requested = false;
    try {
        thread = std::thread(pump);
    } catch (...) {
        finished = true;
        return false;
    }
    for (int i = 0; i < wait_steps && !running && !finished; ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    if (!running && finished && thread.joinable()) thread.join();
#ifdef VYRAL_SMTC_TEST_BEFORE_START_RESULT
    VYRAL_SMTC_TEST_BEFORE_START_RESULT();
#endif
    // Completion can race this return; only joining transfers ownership back.
    return thread.joinable();
}
