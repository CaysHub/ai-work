import type { Nodes } from 'mdast';
import remarkGfm from 'remark-gfm';
import remarkParse from 'remark-parse';
import { unified } from 'unified';

const parser = unified().use(remarkParse).use(remarkGfm);

export function wecomPlainText(markdown: string) {
  const tree = parser.parse(markdown);
  const definitions = new Map(tree.children.flatMap((node) => node.type === 'definition' ? [[node.identifier, node.url]] : []));
  function render(node: Nodes): string {
    const children = (separator = '') => 'children' in node ? node.children.map(render).filter(Boolean).join(separator) : '';
    const link = (label: string, url?: string) => url && url !== label ? (label ? label + ' ' : '') + url : label;
    switch (node.type) {
      case 'root': return children('\n\n');
      case 'blockquote': return children('\n\n').replace(/^(.+)$/gm, '› $1');
      case 'list': return node.children.map((item, index) => {
        const bullet = node.ordered ? (Number(node.start ?? 1) + index) + '. ' : '• ';
        const checkbox = item.checked == null ? '' : item.checked ? '☑ ' : '☐ ';
        return bullet + checkbox + render(item).replaceAll('\n', '\n  ');
      }).join('\n');
      case 'listItem': return children('\n');
      case 'table': return children('\n');
      case 'tableRow': return node.children.map(render).join(' ｜ ');
      case 'link': return link(children(), node.url);
      case 'linkReference': return link(children(), definitions.get(node.identifier));
      case 'image': return link(node.alt ?? '', node.url);
      case 'imageReference': return link(node.alt ?? '', definitions.get(node.identifier));
      case 'footnoteReference': return '［' + (node.label ?? node.identifier) + '］';
      case 'footnoteDefinition': return '［' + (node.label ?? node.identifier) + '］' + children('\n');
      case 'break': return '\n';
      case 'text':
      case 'code':
      case 'inlineCode': return node.value;
      case 'html': return node.value.replace(/<br\s*\/?>/gi, '\n').replace(/<\/?[a-z][^>]*>/gi, '');
      case 'definition':
      case 'thematicBreak': return '';
      default: return children();
    }
  }
  return render(tree).trim();
}

export function splitWecomReply(body: string, suffix: string, maxBytes = 2000) {
  const text = [body, suffix].filter(Boolean).join('\n\n').trim();
  if (Buffer.byteLength(text) <= maxBytes) return text ? [text] : [];
  // Reserve bytes for ordering labels and keep the entire link or warning on the final message.
  const capacity = maxBytes - 24;
  const parts: string[] = [];
  const append = (source: string) => {
    let remaining = source;
    while (Buffer.byteLength(remaining) > capacity) {
      let length = 0;
      let bytes = 0;
      for (const character of remaining) {
        bytes += Buffer.byteLength(character);
        if (bytes > capacity) break;
        length += character.length;
      }
      const prefix = remaining.slice(0, length);
      const minimum = Math.floor(length / 2);
      const paragraph = prefix.lastIndexOf('\n\n');
      const line = prefix.lastIndexOf('\n');
      const sentence = Math.max(...['。', '！', '？'].map((punctuation) => prefix.lastIndexOf(punctuation)));
      const space = prefix.lastIndexOf(' ');
      let end = paragraph >= minimum ? paragraph + 2 : line >= minimum ? line + 1 :
        sentence >= minimum ? sentence + 1 : space >= minimum ? space + 1 : length;
      const partialUrl = /https?:\/\/[^\s]*$/i.exec(prefix);
      if (partialUrl && partialUrl.index > 0 && !/\s/.test(remaining[length] ?? ' ')) {
        const url = /^https?:\/\/\S+/i.exec(remaining.slice(partialUrl.index))?.[0];
        if (url && Buffer.byteLength(url) <= capacity) end = Math.min(end, partialUrl.index);
      }
      parts.push(remaining.slice(0, end).trimEnd());
      remaining = remaining.slice(end).trimStart();
    }
    if (remaining) parts.push(remaining);
  };
  append(body.trim());
  if (suffix) {
    const last = parts.at(-1);
    if (last && Buffer.byteLength(last + '\n\n' + suffix) <= capacity) parts[parts.length - 1] = last + '\n\n' + suffix;
    else append(suffix);
  }
  return parts.map((part, index) => '【' + (index + 1) + '/' + parts.length + '】\n' + part);
}
