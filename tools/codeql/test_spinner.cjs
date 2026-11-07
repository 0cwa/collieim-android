// Exercise the actual viewer scripts with hostile values, without dependencies.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const assets = path.resolve(__dirname, '../../lib/spinner/src/main/assets');

test('database selection remains one encoded parameter on the same origin', () => {
  for (const name of ['signal', 'db&other=1#fragment', '<svg onload=alert(1)>', 'javascript:alert(1)', '雪 % + / ?']) {
    const selector = {};
    const theme = {};
    const window = { location: { href: 'http://127.0.0.1:8080/query?old=1#old' } };
    const document = {
      querySelectorAll: () => [],
      querySelector: (selectorName) => selectorName === '#database-selector' ? selector : theme,
    };
    vm.runInNewContext(fs.readFileSync(path.join(assets, 'js/main.js'), 'utf8'), {
      document, window, URL, localStorage: { getItem: () => null },
    });
    selector.onchange({ target: { value: name } });
    const result = new URL(window.location.href);
    assert.equal(result.origin, 'http://127.0.0.1:8080');
    assert.equal(result.pathname, '/query');
    assert.equal(result.hash, '');
    assert.deepEqual([...result.searchParams.entries()], [['db', name]]);
  }
});

function queryFixture(history) {
  const elements = new Map();
  class Element {
    constructor(tagName) { this.tagName = tagName; this.children = []; }
    set id(value) { this.identifier = value; elements.set(value, this); }
    get id() { return this.identifier; }
    set textContent(value) { this.text = String(value); }
    get textContent() { return this.text || ''; }
    get innerText() { return this.textContent; }
    set innerHTML(value) { throw new Error('Query history must never be parsed as HTML: ' + value); }
    appendChild(child) { this.children.push(child); return child; }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children = children; }
    addEventListener() {}
  }
  for (const id of ['history-container', 'query-form', 'query']) {
    const element = new Element(id === 'history-container' ? 'table' : 'input');
    element.id = id;
  }
  let editorValue;
  const editor = {
    setFontSize() {}, commands: { addCommand() {} },
    setValue(value) { editorValue = value; },
  };
  const context = vm.createContext({
    document: {
      getElementById: (id) => elements.get(id),
      createElement: (tag) => new Element(tag),
      querySelector: () => new Element('div'),
      body: { getAttribute: () => null },
    },
    localStorage: { getItem: () => JSON.stringify(history) },
    initializeTooltips() {},
    ace: { edit: () => editor },
  });
  const template = fs.readFileSync(path.join(assets, 'query.hbs'), 'utf8');
  const script = template.match(/<script type="text\/javascript">([\s\S]*?)<\/script>/)[1];
  vm.runInContext(script, context);
  return { elements, context, value: () => editorValue };
}

test('saved SQL is rendered literally and remains selectable', () => {
  const payload = 'SELECT "<img src=x onerror=alert(1)> & <script>alert(2)</script>"';
  const fixture = queryFixture([payload, 'SELECT 2']);
  const table = fixture.elements.get('history-container');
  assert.equal(table.children.length, 2);
  assert.equal(fixture.elements.get('history-item-0').textContent, payload);
  table.children[0].children[0].children[0].onclick();
  assert.equal(fixture.value(), payload);
  vm.runInContext('renderQueryHistory()', fixture.context);
  assert.equal(table.children.length, 2, 'rerendering must not duplicate history');
});

test('empty history is displayed as text', () => {
  const fixture = queryFixture([]);
  const table = fixture.elements.get('history-container');
  assert.equal(table.children.length, 1);
  assert.equal(table.children[0].textContent, 'None');
});
