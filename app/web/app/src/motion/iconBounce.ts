import { shouldReduceAnimations } from '@/state/motion';

const CONTROL_SELECTOR = 'button:not(:disabled), a[href], [role="button"], [role="menuitem"]';
const DURATION_MS = 510;
const STAGGER_MS = 18;

/** Animate the independent vector layers inside the activated control, without scaling the whole icon. */
export function animateIconLayers(control: Element): void {
  if (shouldReduceAnimations()) return;

  const icons = control.querySelectorAll<SVGSVGElement>('svg');
  icons.forEach((icon) => {
    const layers = Array.from(icon.children).filter((child): child is SVGElement => child instanceof SVGElement);
    layers.forEach((layer, index) => {
      if (typeof layer.animate !== 'function') return;
      layer.getAnimations?.().forEach((animation) => animation.cancel());
      layer.style.transformBox = 'fill-box';
      layer.style.transformOrigin = 'center';
      layer.animate(
        [
          { transform: 'translateY(0) scale(1) rotate(0deg)', offset: 0 },
          { transform: 'translateY(2px) scale(0.84) rotate(-2deg)', offset: 0.24 },
          { transform: 'translateY(-3px) scale(1.12) rotate(2deg)', offset: 0.62 },
          { transform: 'translateY(0) scale(1) rotate(0deg)', offset: 1 },
        ],
        {
          duration: DURATION_MS,
          delay: index * STAGGER_MS,
          easing: 'cubic-bezier(0.34, 1.56, 0.64, 1)',
        },
      );
    });
  });
}

/** Delegation covers current and lazily-mounted controls without per-icon React state. */
export function installIconLayerBounce(root: Document = document): () => void {
  const onClick = (event: Event) => {
    const target = event.target;
    if (!(target instanceof Element)) return;
    const control = target.closest(CONTROL_SELECTOR);
    if (control) animateIconLayers(control);
  };
  root.addEventListener('click', onClick);
  return () => root.removeEventListener('click', onClick);
}
