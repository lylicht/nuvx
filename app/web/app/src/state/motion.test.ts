// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { animateIconLayers } from '@/motion/iconBounce';

function stubAnimate() {
  const original = Object.getOwnPropertyDescriptor(SVGElement.prototype, 'animate');
  const animate = vi.fn((..._args: unknown[]) => ({ cancel: vi.fn() }) as unknown as Animation);
  Object.defineProperty(SVGElement.prototype, 'animate', { configurable: true, value: animate });
  return {
    animate,
    restore: () => {
      if (original) Object.defineProperty(SVGElement.prototype, 'animate', original);
      else Reflect.deleteProperty(SVGElement.prototype, 'animate');
    },
  };
}

describe('icon layer motion', () => {
  afterEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-reduce-animations');
  });

  it('animates each vector path independently with staggered delays', () => {
    const { animate, restore } = stubAnimate();
    const control = document.createElement('button');
    control.innerHTML = '<svg><path/><path/><circle/></svg>';

    animateIconLayers(control);

    expect(animate).toHaveBeenCalledTimes(3);
    expect(animate.mock.calls[0]?.[1]).toMatchObject({ delay: 0 });
    expect(animate.mock.calls[1]?.[1]).toMatchObject({ delay: 18 });
    expect(animate.mock.calls[2]?.[1]).toMatchObject({ delay: 36 });
    restore();
  });

  it('does not animate any layer when the reduced-animation setting is enabled', () => {
    localStorage.setItem('vyral:reduce-animations', '1');
    const { animate, restore } = stubAnimate();
    const control = document.createElement('button');
    control.innerHTML = '<svg><path/><path/></svg>';

    animateIconLayers(control);

    expect(animate).not.toHaveBeenCalled();
    restore();
  });
});
