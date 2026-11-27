(() => {
  const API = 'https://api.github.com/repos/syrrnight/VYRAL';
  // Only titles that lead with it: "Bug: … the drag-to-play feature" is still a bug report.
  const FEATURE = /^\W*(feature|feat|enhancement|proposal|idea)\b/i;

  // One entry per person: issues they opened, which of those propose a feature, PRs, and commits.
  const collect = (issues, contributors) => {
    const people = new Map();
    const human = (u) => u && u.type !== 'Bot' && !/\[bot\]$/i.test(u.login);
    const person = (u) => {
      if (!people.has(u.login)) people.set(u.login, { login: u.login, avatar: u.avatar_url, url: u.html_url, issues: 0, features: 0, prs: 0, commits: 0 });
      return people.get(u.login);
    };
    for (const item of issues) {
      if (!human(item.user)) continue;
      const p = person(item.user);
      if (item.pull_request) p.prs++;
      else if ((item.labels || []).some((l) => l.name === 'enhancement') || FEATURE.test(item.title || '')) p.features++;
      else p.issues++;
    }
    for (const c of contributors) if (human(c)) person(c).commits += c.contributions || 0;
    const total = (p) => p.issues + p.features + p.prs + p.commits;
    return [...people.values()].sort((a, b) => total(b) - total(a) || a.login.localeCompare(b.login));
  };

  const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
  const roles = (p) => [
    p.features && plural(p.features, 'feature idea', 'feature ideas'),
    p.issues && plural(p.issues, 'issue', 'issues'),
    p.prs && plural(p.prs, 'pull request', 'pull requests'),
    p.commits && plural(p.commits, 'commit', 'commits'),
  ].filter(Boolean);

  if (typeof document === 'undefined') {
    globalThis.nuvxContributors = { collect, roles };
    return;
  }
  const root = document.getElementById('contributors');
  if (!root) return;

  const pages = async (path) => {
    const all = [];
    for (let page = 1; page <= 20; page++) {
      const res = await fetch(`${API}/${path}${path.includes('?') ? '&' : '?'}per_page=100&page=${page}`, { headers: { Accept: 'application/vnd.github+json' } });
      if (!res.ok) throw new Error(`GitHub answered ${res.status}`);
      const batch = await res.json();
      all.push(...batch);
      if (batch.length < 100) break;
    }
    return all;
  };

  const el = (tag, cls, text) => {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  };

  Promise.all([pages('issues?state=all'), pages('contributors')])
    .then(([issues, contributors]) => {
      const people = collect(issues, contributors);
      const list = el('ul', 'people');
      for (const p of people) {
        const li = el('li', 'person');
        const a = el('a');
        a.href = p.url;
        a.rel = 'noopener noreferrer';
        const img = el('img');
        img.src = `${p.avatar}${p.avatar.includes('?') ? '&' : '?'}s=96`;
        img.alt = '';
        img.width = 48;
        img.height = 48;
        img.loading = 'lazy';
        a.append(img, el('strong', null, p.login));
        li.append(a, el('span', 'person-roles', roles(p).join(' · ')));
        list.append(li);
      }
      root.replaceChildren(el('p', 'cl-status', `${people.length} people have helped build nuvx.`), list);
    })
    .catch(() => {
      const p = el('p', 'cl-status', 'GitHub could not be reached right now. See everyone on ');
      const a = el('a', null, 'GitHub');
      a.href = 'https://github.com/lylicht/nuvx/graphs/contributors';
      p.append(a, '.');
      root.replaceChildren(p);
    })
    .finally(() => root.setAttribute('aria-busy', 'false'));
})();
