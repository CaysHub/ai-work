import type { TableProps } from 'antd';
import { Card, Empty } from 'antd';
import type { ReactNode } from 'react';
import { CompactTable } from './CompactTable.tsx';
import { recordPageSize } from './records.config.ts';

export function View({ title, description, action, children }: { title: string; description: string; action: ReactNode; children: ReactNode }) {
  return <section className="view"><header className="view-heading"><div><h1>{title}</h1><p>{description}</p></div>{action}</header>{children}</section>;
}

export function Records<T extends { id: string }>({ rows, columns, children }: {
  rows: T[]; columns: TableProps<T>['columns']; children: ReactNode;
}) {
  return <Card className="records">{rows.length ? <CompactTable<T> rowKey="id" dataSource={rows} columns={columns} pagination={{ pageSize: recordPageSize, hideOnSinglePage: true }} /> :
    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={children} />}</Card>;
}
