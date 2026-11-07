import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const assets = new URL('../../lib/spinner/src/main/assets/', import.meta.url);
const mainScript = readFileSync(new URL('js/main.js', assets), 'utf8');
const queryTemplate = readFileSync(new URL('query.hbs', assets), 'utf8');
const queryScript = queryTemplate.match(/<script type="text\/javascript">([\s\S]*?)<\/script>/)[1];

class Element {
  constructor(tagName = 'div') {
    this.tagName = tagName;
    this.children = [];
    this.listeners = new Map();
    this.attributes = new Map();
    this.style = {};
    this.textContent = '';
  }
  set innerHTML(value) {
    assert.fail('Untrusted history must never be parsed as HTML: ' + value);
  }
  appendChild(child) { this.children.push(child); }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; }
  addEventListener(name, callback) { this.listeners.set(name, callback); }
  getAttribute(name) { return this.attributes.get(name); }
  setAttribute(name, value) { this.attributes.set(name, value); }
  removeAttribute(name) { this.attributes.delete(name); }
}

function page(history = '[]') {
  const elements = new Map([
    ['database-selector', new Element('select')],
    ['theme-toggle', new Element('button')],
    ['history-container', new Element('table')],
    ['query-form', new Element('form')],
    ['query-input', new Element('div')],
    ['query', new Element('input')],
  ]);
  const controls = [new Element('input'), new Element('input'), new Element('button'), new Element('button')];
  const storage = new Map([['query-history', history]]);
  const editor = {
    value: '',
    commands: { addCommand() {} },
    setFontSize() {},
    getValue() { return this.value; },
    setValue(value) { this.value = value; },
  };
  const context = vm.createContext({
    document: {
      body: new Element('body'),
      querySelectorAll(selector) {
        return selector === '#query-form input[type="submit"], #query-form button' ? controls : [];
      },
      querySelector(selector) { return elements.get(selector.slice(1)); },
      getElementById(id) { return elements.get(id); },
      createElement(tagName) { return new Element(tagName); },
    },
    window: { location: new URL('https://spinner.example/query?old=1#results') },
    URLSearchParams,
    localStorage: {
      getItem(key) { return storage.get(key) ?? null; },
      setItem(key, value) { storage.set(key, value); },
      removeItem(key) { storage.delete(key); },
    },
    initializeTooltips() {},
    ace: { require() { return {}; }, edit() { return editor; } },
    sqlFormatter: { format(value) { return value; } },
    console,
  });
  return { context, elements, controls, editor, storage };
}

test('a failed pinned dependency prevents Ace from starting its unverified dynamic loader', () => {
  for (const missing of ['sqlFormatter', 'ace', 'ace/mode/sql', 'ace/theme/github', 'ace/theme/github_dark']) {
    const state = page();
    state.context.ace.edit = () => assert.fail('Ace must not initialize with a missing pinned dependency');
    if (missing === 'ace' || missing === 'sqlFormatter') {
      delete state.context[missing];
    } else {
      state.context.ace.require = name => name === missing ? undefined : {};
    }
    vm.runInContext(queryScript, state.context);
    assert.equal(vm.runInContext('typeof editor', state.context), 'undefined');
    assert.equal(state.elements.get('query-input').textContent, 'The query editor could not load. Reload the page to retry.');
    for (const control of state.controls) assert.equal(control.disabled, true);
  }
});

test('database selection preserves the page and treats every database name as data', () => {
  const state = page();
  vm.runInContext(mainScript, state.context);
  for (const value of [
    'signal.db', 'a b', 'a&admin=1#frag', 'a?b=c', 'a+b%20c',
    'javascript:alert(1)', '//attacker.invalid/path', '<img src=x onerror=alert(1)>', '数据库',
  ]) {
    state.elements.get('database-selector').onchange({ target: { value } });
    const url = state.context.window.location;
    assert.equal(url.origin, 'https://spinner.example');
    assert.equal(url.pathname, '/query');
    assert.equal(url.hash, '#results');
    assert.deepEqual([...url.searchParams.entries()], [['db', value]]);
  }
});

test('stored SQL is rendered as text and loaded into the editor unchanged', () => {
  const payload = `  SELECT '<img src=x onerror=alert(1)> & "quotes"' AS payload;\n-- preserve whitespace\t\n`;
  const state = page(JSON.stringify([payload]));
  vm.runInContext(queryScript, state.context);
  const row = state.elements.get('history-container').children[0];
  assert.equal(row.tagName, 'tr');
  const [buttonCell, queryCell] = row.children;
  assert.equal(queryCell.textContent, payload);
  assert.equal(queryCell.children.length, 0);
  const button = buttonCell.children[0];
  assert.equal(button.type, 'button');
  button.listeners.get('click')();
  assert.equal(state.editor.value, payload);
});

test('rerendering query history replaces existing rows', () => {
  const state = page(JSON.stringify(['SELECT 1;', 'SELECT 2;']));
  vm.runInContext(queryScript, state.context);
  vm.runInContext('renderQueryHistory()', state.context);
  assert.equal(state.elements.get('history-container').children.length, 2);
});

test('invalid saved history cannot prevent editor initialization', () => {
  for (const history of ['invalid JSON', 'null', '{}', '42']) {
    const state = page(history);
    vm.runInContext(queryScript, state.context);
    const row = state.elements.get('history-container').children[0];
    assert.equal(row.children[0].children[0].textContent, 'None');
    assert.equal(vm.runInContext('editor', state.context), state.editor);
  }
});

test('history accepts only strings and keeps its existing 25-query bound', () => {
  const history = [null, {}, 4, ...Array.from({ length: 30 }, (_, i) => `SELECT ${i};`)];
  const state = page(JSON.stringify(history));
  vm.runInContext(queryScript, state.context);
  const rows = state.elements.get('history-container').children;
  assert.equal(rows.length, 25);
  assert.equal(rows[0].children[1].textContent, 'SELECT 0;');
  assert.equal(rows[24].children[1].textContent, 'SELECT 24;');
});

test('every CDN script is version-pinned and requires a SHA-384 integrity check', () => {
  const scripts = [...queryTemplate.matchAll(/<script\b([^>]*)>/g)]
    .map(match => Object.fromEntries([...match[1].matchAll(/([\w-]+)="([^"]*)"/g)]
      .map(attribute => [attribute[1], attribute[2]])))
    .filter(attributes => attributes.src && /^(?:https?:)?\/\//.test(attributes.src));
  assert.equal(scripts.length, 5);
  for (const script of scripts) {
    const url = new URL(script.src);
    assert.equal(url.protocol, 'https:');
    assert.equal(url.hostname, 'cdnjs.cloudflare.com');
    assert.match(url.pathname, /^\/ajax\/libs\/(?:sql-formatter\/4\.0\.2|ace\/1\.32\.5)\/[^/]+\.js$/);
    assert.match(script.integrity, /^sha384-[A-Za-z0-9+/]{64}$/);
    assert.equal(Buffer.from(script.integrity.slice(7), 'base64').length, 48);
    assert.equal(script.crossorigin, 'anonymous');
  }
});
