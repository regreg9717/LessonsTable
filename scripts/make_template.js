// 生成示例课表模板 课表模板.xlsx（教务系统列表格式；不依赖第三方库，zip 使用 STORE 方式）
const fs = require('fs');
const path = require('path');

function crc32(buf) {
  let table = crc32.table;
  if (!table) {
    table = crc32.table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      table[n] = c;
    }
  }
  let c = 0 ^ -1;
  for (let i = 0; i < buf.length; i++) c = (c >>> 8) ^ table[(c ^ buf[i]) & 0xff];
  return (c ^ -1) >>> 0;
}

function buildZip(files) {
  const chunks = [];
  const central = [];
  let offset = 0;
  for (const [name, content] of files) {
    const nameBuf = Buffer.from(name, 'utf8');
    const data = Buffer.from(content, 'utf8');
    const crc = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6);      // UTF-8 flag
    local.writeUInt16LE(0, 8);           // method: store
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    chunks.push(local, nameBuf, data);

    const cd = Buffer.alloc(46);
    cd.writeUInt32LE(0x02014b50, 0);
    cd.writeUInt16LE(20, 4); cd.writeUInt16LE(20, 6);
    cd.writeUInt16LE(0x0800, 8);
    cd.writeUInt32LE(crc, 16);
    cd.writeUInt32LE(data.length, 20);
    cd.writeUInt32LE(data.length, 24);
    cd.writeUInt16LE(nameBuf.length, 28);
    cd.writeUInt32LE(offset, 42);
    central.push(Buffer.concat([cd, nameBuf]));
    offset += 30 + nameBuf.length + data.length;
  }
  const cdBuf = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(files.length, 8);
  end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(cdBuf.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...chunks, cdBuf, end]);
}

const HEADERS = ['课程号', '课程名', '课序号', '开课单位', '学分', '上课周次', '上课星期',
  '开始节次', '结束节次', '上课教师', '教室名称', '课程性质', '课程类别', '校公选课类别'];

const SAMPLE_ROWS = [
  ['', '高等数学', '', '', '3', '1-15周', '星期一', '1', '2', '王老师', 'A栋301', '', '', ''],
  ['', '大学英语', '', '', '2', '1-15周(双)', '星期一', '3', '4', '李老师', 'B栋201', '', '', ''],
  ['', '数据结构', '', '', '3', '1-15周', '星期二', '3', '4', '张老师', 'C栋105', '', '', ''],
  ['', '操作系统', '', '', '3', '1-15周', '星期三', '5', '6', '陈老师', 'C栋202', '', '', ''],
  ['', '线性代数', '', '', '3', '1-15周(单)', '星期四', '1', '2', '赵老师', 'B栋110', '', '', ''],
  ['', '计算机网络', '', '', '3', '1-15周', '星期五', '5', '6', '孙老师', 'D栋303', '', '', ''],
  ['', '体育', '', '', '1', '1-15周', '星期五', '7', '8', '刘老师', '操场', '', '', ''],
];

const esc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const colRef = i => (i < 26 ? String.fromCharCode(65 + i)
  : String.fromCharCode(64 + Math.floor(i / 26)) + String.fromCharCode(65 + i % 26));
const cell = (c, v) => `<c r="${colRef(c)}" t="inlineStr"><is><t>${esc(v)}</t></is></c>`;

let rows = '<row r="1">' + HEADERS.map((h, i) => cell(i, h)).join('') + '</row>';
SAMPLE_ROWS.forEach((row, r) => {
  rows += `<row r="${r + 2}">` + row.map((v, c) => v ? cell(c, v) : '').join('') + '</row>';
});

const files = [
  ['[Content_Types].xml', `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>`],
  ['_rels/.rels', `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`],
  ['xl/workbook.xml', `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="课表" sheetId="1" r:id="rId1"/></sheets></workbook>`],
  ['xl/_rels/workbook.xml.rels', `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>`],
  ['xl/worksheets/sheet1.xml', `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${rows}</sheetData></worksheet>`],
];

const out = path.join(__dirname, '..', 'template', '课表模板.xlsx');
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, buildZip(files));
console.log('written:', out);
