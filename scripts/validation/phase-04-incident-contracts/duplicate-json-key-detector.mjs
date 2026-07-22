export class DuplicateJsonKeyError extends Error {}

export function rejectDuplicateJsonKeys(source) {
  let index = 0;

  function skipWhitespace() {
    while (index < source.length && /\s/u.test(source[index])) index += 1;
  }

  function expect(character) {
    if (source[index] !== character) throw new SyntaxError("unexpected JSON token");
    index += 1;
  }

  function parseString() {
    const start = index;
    expect('"');
    while (index < source.length) {
      if (source[index] === "\\") {
        index += 2;
      } else if (source[index] === '"') {
        index += 1;
        return JSON.parse(source.slice(start, index));
      } else {
        index += 1;
      }
    }
    throw new SyntaxError("unterminated JSON string");
  }

  function parseObject() {
    index += 1;
    skipWhitespace();
    const keys = new Set();
    if (source[index] === "}") {
      index += 1;
      return;
    }
    while (index < source.length) {
      skipWhitespace();
      if (source[index] !== '"') throw new SyntaxError("object key must be a string");
      const key = parseString();
      if (keys.has(key)) throw new DuplicateJsonKeyError("duplicate JSON key");
      keys.add(key);
      skipWhitespace();
      expect(":");
      parseValue();
      skipWhitespace();
      if (source[index] === "}") {
        index += 1;
        return;
      }
      expect(",");
    }
    throw new SyntaxError("unterminated JSON object");
  }

  function parseArray() {
    index += 1;
    skipWhitespace();
    if (source[index] === "]") {
      index += 1;
      return;
    }
    while (index < source.length) {
      parseValue();
      skipWhitespace();
      if (source[index] === "]") {
        index += 1;
        return;
      }
      expect(",");
    }
    throw new SyntaxError("unterminated JSON array");
  }

  function parseValue() {
    skipWhitespace();
    const token = source[index];
    if (token === "{") return parseObject();
    if (token === "[") return parseArray();
    if (token === '"') return parseString();
    const primitive = source.slice(index).match(
      /^(?:true|false|null|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)/u,
    );
    if (!primitive) throw new SyntaxError("invalid JSON value");
    index += primitive[0].length;
  }

  parseValue();
  skipWhitespace();
  if (index !== source.length) throw new SyntaxError("trailing JSON content");
}
