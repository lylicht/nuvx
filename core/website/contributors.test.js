const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { runInNewContext } = require('node:vm');
const { test } = require('node:test');

const context = {};
runInNewContext(readFileSync(`${__dirname}/contributors.js`, 'utf8'), { globalThis: context });
const { collect } = context.nuvxContributors;
// Arrays made inside the vm context have another prototype; copy them for deepEqual.
const roles = (p) => Array.from(context.nuvxContributors.roles(p));

const user = (login, type = 'User') => ({ login, type, avatar_url: `https://avatars.example/${login}`, html_url: `https://github.com/${login}` });

test('groups issues, feature ideas, pull requests and commits per person, without bots', () => {
  const people = collect(
    [
      { user: user('ana'), title: '[Bug] Queue duplicates a song', labels: [] },
      { user: user('ana'), title: 'Feature Request: mini player', labels: [] },
      { user: user('ana'), title: 'Bug: song duplicated when using the "play next" feature', labels: [] },
      { user: user('ben'), title: 'Add tray controls', labels: [], pull_request: {} },
      { user: user('cy'), title: 'Better lyrics', labels: [{ name: 'enhancement' }] },
      { user: user('dependabot[bot]', 'Bot'), title: 'Bump x', labels: [], pull_request: {} },
    ],
    [user('ben'), user('renovate[bot]', 'Bot')].map((u, i) => ({ ...u, contributions: i ? 99 : 5 })),
  );
  assert.deepEqual(Array.from(people, (p) => p.login), ['ben', 'ana', 'cy']);
  assert.deepEqual(roles(people[0]), ['1 pull request', '5 commits']);
  assert.deepEqual(roles(people[1]), ['1 feature idea', '2 issues']);
  assert.deepEqual(roles(people[2]), ['1 feature idea']);
});
