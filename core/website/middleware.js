// Runs before Vercel's file lookup, so contributors.nuvx.lol/ can show the contributors page instead of index.html.
export const config = { matcher: '/' };

export default function middleware(request) {
  const url = new URL(request.url);
  if (url.hostname !== 'contributors.nuvx.lol') return;
  url.pathname = '/contributors';
  return new Response(null, { headers: { 'x-middleware-rewrite': url.toString() } });
}
