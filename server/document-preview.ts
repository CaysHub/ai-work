import { extname } from 'node:path';
import { inflateRawSync } from 'node:zlib';
import AdmZip from 'adm-zip';
import { DOMParser } from '@xmldom/xmldom';
import type { Element } from '@xmldom/xmldom';
import type { DocumentPreview } from '../shared/types.ts';

const textLimit = 80000;
const xmlPartLimit = 4 * 1024 * 1024;

export function documentInlineType(name: string, bytes: Buffer) {
  const extension = extname(name).toLowerCase();
  if (extension === '.pdf' && bytes.subarray(0, 5).toString('ascii') === '%PDF-') return 'application/pdf';
  if (extension === '.png' && bytes.subarray(0, 8).toString('hex') === '89504e470d0a1a0a') return 'image/png';
  if (['.jpg', '.jpeg'].includes(extension) && bytes.subarray(0, 3).toString('hex') === 'ffd8ff') return 'image/jpeg';
  if (extension === '.gif' && ['GIF87a', 'GIF89a'].includes(bytes.subarray(0, 6).toString('ascii'))) return 'image/gif';
  if (extension === '.webp' && bytes.subarray(0, 4).toString('ascii') === 'RIFF' &&
    bytes.subarray(8, 12).toString('ascii') === 'WEBP') return 'image/webp';
  return null;
}

function xmlPart(zip: AdmZip, name: string) {
  const entry = zip.getEntry(name);
  if (!entry) return null;
  if (entry.header.size > xmlPartLimit) throw new Error('预览内容超限');
  const compressed = entry.getCompressedData();
  const bytes = entry.header.method === 8 ? inflateRawSync(compressed, { maxOutputLength: xmlPartLimit }) : compressed;
  if (![0, 8].includes(entry.header.method) || bytes.length !== entry.header.size) throw new Error('文档内容无效');
  const xml = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  if (/<!DOCTYPE|<!ENTITY/i.test(xml)) throw new Error('文档包含不支持的 XML 声明');
  return new DOMParser().parseFromString(xml, 'application/xml');
}

function descendants(node: { getElementsByTagNameNS(namespace: string, name: string): ArrayLike<Element> }, name: string) {
  return Array.from(node.getElementsByTagNameNS('*', name));
}
const textNodes = (node: Element) => descendants(node, 't').map((element) => element.textContent ?? '').join('');

function workbookSheets(zip: AdmZip, workbook: ReturnType<typeof xmlPart>) {
  const rels = xmlPart(zip, 'xl/_rels/workbook.xml.rels');
  if (workbook && rels) {
    const paths = new Map(descendants(rels, 'Relationship').map((rel) =>
      [rel.getAttribute('Id'), (rel.getAttribute('Target') ?? '').replace(/^(?:\/xl\/)?/, 'xl/')]));
    const sheets = descendants(workbook, 'sheet').flatMap((sheet) => {
      const path = paths.get(sheet.getAttribute('r:id'));
      return path && /^xl\/worksheets\/[\w.-]+\.xml$/i.test(path) ? [{ path, name: sheet.getAttribute('name') }] : [];
    });
    if (sheets.length) return sheets;
  }
  return zip.getEntries().map((entry) => entry.entryName)
    .filter((path) => /^xl\/worksheets\/sheet\d+\.xml$/.test(path))
    .sort((a, b) => a.localeCompare(b, undefined, { numeric: true })).map((path) => ({ path, name: null }));
}

function workbookDateFormatter(zip: AdmZip, workbook: ReturnType<typeof xmlPart>) {
  const styles = xmlPart(zip, 'xl/styles.xml');
  const cellStyles = styles && descendants(styles, 'cellXfs')[0];
  const custom = new Map((styles ? descendants(styles, 'numFmt') : []).map((element) =>
    [element.getAttribute('numFmtId'), element.getAttribute('formatCode') ?? '']));
  const dateStyles = (cellStyles ? descendants(cellStyles, 'xf') : []).map((xf) => {
    const id = xf.getAttribute('numFmtId');
    const format = (custom.get(id) ?? '').split(';')[0].replace(/"[^"]*"|\\.|\[[^\]]*\]/g, '').toLowerCase();
    return {
      date: /[yd]/.test(format) || [14, 15, 16, 17, 22].includes(Number(id)),
      time: /[hs]/.test(format) || [18, 19, 20, 21, 22, 45, 47].includes(Number(id)),
    };
  });
  const date1904 = workbook && ['1', 'true'].includes(descendants(workbook, 'workbookPr')[0]?.getAttribute('date1904') ?? '');
  return (value: string, style: string) => {
    const format = dateStyles[Number(style)];
    const serial = Number(value);
    if (!value || !format || (!format.date && !format.time) || !(serial >= 0 && serial < 2_958_466)) return value;
    const epoch = date1904 ? Date.UTC(1904, 0, 1) : Date.UTC(1899, 11, serial < 60 ? 31 : 30);
    const iso = new Date(epoch + Math.round(serial * 86400) * 1000).toISOString();
    return format.date ? format.time ? iso.slice(0, 16).replace('T', ' ') : iso.slice(0, 10) : iso.slice(11, 16);
  };
}

function officeText(extension: string, data: Buffer) {
  const zip = new AdmZip(data);
  if (extension === '.docx') {
    const main = xmlPart(zip, 'word/document.xml');
    if (!main) return null;
    return descendants(main, 'p').map(textNodes).join('\n');
  }
  if (extension === '.pptx') {
    const slides = zip.getEntries().map((entry) => entry.entryName).filter((name) => /^ppt\/slides\/slide\d+\.xml$/.test(name));
    slides.sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
    if (!slides.length) return null;
    let output = '';
    for (const [index, name] of slides.slice(0, 12).entries()) {
      const slide = xmlPart(zip, name);
      if (slide) output += '第 ' + (index + 1) + ' 页\n' + descendants(slide, 'p').map(textNodes).join('\n') + '\n\n';
      if (output.length >= textLimit) break;
    }
    return { text: output, truncated: slides.length > 12 };
  }
  const sharedStrings = xmlPart(zip, 'xl/sharedStrings.xml');
  const shared = sharedStrings ? descendants(sharedStrings, 'si').map(textNodes) : [];
  const workbook = xmlPart(zip, 'xl/workbook.xml');
  const sheets = workbookSheets(zip, workbook);
  if (!sheets.length) return null;
  const formatDate = workbookDateFormatter(zip, workbook);
  let output = '';
  let truncated = sheets.length > 5;
  for (const [index, { path, name }] of sheets.slice(0, 5).entries()) {
    const sheet = xmlPart(zip, path);
    if (!sheet) continue;
    const rows = descendants(sheet, 'row');
    truncated ||= rows.length > 200;
    const lines = rows.slice(0, 200).map((row) => {
      const values: string[] = [];
      const cells = descendants(row, 'c');
      truncated ||= cells.length > 40;
      for (const cell of cells.slice(0, 40)) {
        const column = /^[A-Z]{1,3}/.exec(cell.getAttribute('r') ?? '')?.[0];
        const position = column
          ? Array.from(column).reduce((offset, letter) => offset * 26 + letter.charCodeAt(0) - 64, 0) - 1
          : values.length;
        if (position >= 40) {
          truncated = true;
          continue;
        }
        const value = descendants(cell, 'v')[0]?.textContent ?? '';
        const kind = cell.getAttribute('t');
        values[position] = kind === 's' ? shared[Number(value)] ?? '' :
          kind === 'inlineStr' ? textNodes(cell) :
            !kind || kind === 'n' ? formatDate(value, cell.getAttribute('s') ?? '0') : value;
      }
      return values.join('\t');
    });
    output += '工作表 ' + (name || (index + 1)) + '\n' + lines.join('\n') + '\n\n';
    if (output.length >= textLimit) break;
  }
  return { text: output, truncated };
}

export function previewDocument(name: string, data: Buffer): DocumentPreview {
  const mime = documentInlineType(name, data);
  if (mime) return { kind: mime === 'application/pdf' ? 'pdf' : 'image' };
  const extension = extname(name).toLowerCase();
  try {
    let text: string;
    let truncated = false;
    if (['.docx', '.xlsx', '.pptx'].includes(extension)) {
      const content = officeText(extension, data);
      if (content === null) return { kind: 'download' };
      text = typeof content === 'string' ? content : content.text;
      truncated = typeof content !== 'string' && content.truncated;
    } else {
      const prefix = data.subarray(0, textLimit * 4);
      text = new TextDecoder('utf-8', { fatal: true }).decode(prefix, { stream: data.length > prefix.length });
      if (/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(text)) return { kind: 'download' };
      truncated = data.length > prefix.length;
    }
    return { kind: ['.md', '.markdown'].includes(extension) ? 'markdown' : 'text',
      text: text.slice(0, textLimit), truncated: truncated || text.length > textLimit };
  } catch {
    return { kind: 'download' };
  }
}
