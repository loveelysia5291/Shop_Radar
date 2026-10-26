// Read local debug archives as data; never execute their scripts or extract paths.
const fs = require('node:fs');
const zlib = require('node:zlib');
module.exports = function readDiagnosticZip(path) {
  const zip = fs.readFileSync(path);
  let end = zip.length - 22;
  while (end >= Math.max(0, zip.length - 65557) && zip.readUInt32LE(end) !== 0x06054b50) end--;
  if (end < 0 || zip.readUInt32LE(end) !== 0x06054b50) throw new Error('Invalid ZIP directory');
  let cursor = zip.readUInt32LE(end + 16);
  const count = zip.readUInt16LE(end + 10);
  if (count > 20) throw new Error('Too many diagnostic entries');
  const entries = new Map();
  for (let i = 0; i < count; i++) {
    if (zip.readUInt32LE(cursor) !== 0x02014b50) throw new Error('Invalid ZIP entry');
    const method = zip.readUInt16LE(cursor + 10), compressed = zip.readUInt32LE(cursor + 20);
    const length = zip.readUInt32LE(cursor + 24), nameLength = zip.readUInt16LE(cursor + 28);
    const name = zip.subarray(cursor + 46, cursor + 46 + nameLength).toString('utf8');
    const offset = zip.readUInt32LE(cursor + 42);
    if (length > 16 * 1024 * 1024 || zip.readUInt32LE(offset) !== 0x04034b50) throw new Error('Invalid or oversized diagnostic entry');
    const start = offset + 30 + zip.readUInt16LE(offset + 26) + zip.readUInt16LE(offset + 28);
    const data = zip.subarray(start, start + compressed);
    const decoded = method === 8 ? zlib.inflateRawSync(data, {maxOutputLength: 16 * 1024 * 1024}) : method === 0 ? data : null;
    if (!decoded || decoded.length !== length) throw new Error('Invalid diagnostic compression');
    entries.set(name, decoded.toString('utf8'));
    cursor += 46 + nameLength + zip.readUInt16LE(cursor + 30) + zip.readUInt16LE(cursor + 32);
  }
  return entries;
};
