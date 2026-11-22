import React, { useRef } from 'react';
import { Table } from 'antd';
import type { SelectProps, TableProps } from 'antd';
import type { Components } from 'react-markdown';
import { ContentPreview } from './content-preview.tsx';
import type { SkillOption } from './shared.tsx';

export function OverflowText({ text, children, popupClassName }: { text: string; children?: React.ReactNode; popupClassName?: string }) {
  const content = useRef<HTMLSpanElement>(null);
  return <ContentPreview text={text} className={popupClassName} isOverflowing={() => {
    const el = content.current;
    return Boolean(el && el.scrollWidth > el.clientWidth);
  }}>
    <span ref={content} className="table-cell-text">{children ?? text}</span>
  </ContentPreview>;
}

export const skillLabelPreviews: Pick<SelectProps<string[], SkillOption>, 'labelRender' | 'optionRender'> = {
  labelRender: ({ label, value }) => <OverflowText text={String(label ?? value)} />,
  optionRender: ({ data }) => <OverflowText text={data.label} />,
};

function displayText(node: React.ReactNode): string {
  if (typeof node === 'string' || typeof node === 'number') return String(node);
  if (Array.isArray(node)) return node.map(displayText).join('');
  if (React.isValidElement<{ children?: React.ReactNode }>(node)) return displayText(node.props.children);
  return '';
}

export const markdownTableCells: Components = {
  td: ({ node: _node, children, ...props }) => <td {...props}><OverflowText text={displayText(children)}>{children}</OverflowText></td>,
  th: ({ node: _node, children, ...props }) => <th {...props}><OverflowText text={displayText(children)}>{children}</OverflowText></th>,
};

export function CompactTable<T extends object>({ columns, ...props }: TableProps<T>) {
  return <Table<T> {...props} className="compact-table" tableLayout="fixed" columns={columns?.map((column) => {
    if ('children' in column) return column;
    return {
      ...column, ellipsis: false, title: typeof column.title === 'string' ? <OverflowText text={column.title} /> : column.title,
      render(value, row, index) {
        const result = column.render ? column.render(value, row, index) : value;
        return typeof result === 'string' || typeof result === 'number' ? <OverflowText text={String(result)} /> : result;
      },
    };
  })} />;
}
