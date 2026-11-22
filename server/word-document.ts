import { Document, HeadingLevel, Packer, Paragraph } from 'docx';
import { httpError } from './config.ts';

const headings = [HeadingLevel.HEADING_1, HeadingLevel.HEADING_2, HeadingLevel.HEADING_3,
  HeadingLevel.HEADING_4, HeadingLevel.HEADING_5, HeadingLevel.HEADING_6];

export function createWordDocument(title: string, content: string) {
  if (!content.trim() || content.length > 200000 || title.length > 240) throw httpError(400, 'Word 文档正文或标题无效');
  const paragraphs = content.split(/\r?\n/).map((line) => {
    const header = /^(#{1,6})\s+(.*)$/.exec(line);
    if (header) return new Paragraph({ text: header[2], heading: headings[header[1].length - 1] });
    const bullet = /^[-*]\s+(.*)$/.exec(line);
    return bullet ? new Paragraph({ text: bullet[1], bullet: { level: 0 } }) : new Paragraph({ text: line });
  });
  return Packer.toBuffer(new Document({ title, sections: [{
    children: [new Paragraph({ text: title, heading: HeadingLevel.TITLE }), ...paragraphs],
  }] }));
}
