import { CheckOutlined, CopyOutlined } from '@ant-design/icons';
import { Button, Input, Popover } from 'antd';
import type { ComponentProps, ReactElement } from 'react';
import { useEffect, useRef, useState } from 'react';

let waitForPointerMovement = false;

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text);
    return;
  } catch {
    const field = document.createElement('textarea');
    field.value = text;
    field.readOnly = true;
    field.className = 'preview-clipboard';
    const previous = document.activeElement;
    document.body.append(field);
    try {
      field.select();
      if (!document.execCommand('copy')) throw new Error('无法写入剪贴板');
    } finally {
      field.remove();
      if (previous instanceof HTMLElement) previous.focus({ preventScroll: true });
    }
  }
}

export function ContentPreview({ text, isOverflowing, children, className }: {
  text: string; isOverflowing: () => boolean; children: ReactElement; className?: string;
}) {
  const [open, setOpen] = useState(false);
  const [copyState, setCopyState] = useState<'ready' | 'copied' | 'failed'>('ready');
  useEffect(() => {
    if (!open) return;
    const close = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        waitForPointerMovement = true;
        window.addEventListener('pointermove', () => { waitForPointerMovement = false; }, { once: true });
        setOpen(false);
      }
    };
    window.addEventListener('keydown', close, true);
    return () => window.removeEventListener('keydown', close, true);
  }, [open]);
  return <Popover open={open} trigger="hover" placement="bottomLeft" mouseEnterDelay={0.35} mouseLeaveDelay={0.18}
    destroyOnHidden classNames={{ root: ['content-preview-popover', className].filter(Boolean).join(' ') }}
    onOpenChange={(next) => {
      setOpen(Boolean(next && !waitForPointerMovement && text && isOverflowing()));
      if (next) setCopyState('ready');
    }}
    content={<div className="content-preview" onClick={(event) => {
      event.preventDefault();
      event.stopPropagation();
    }}>
      <div className="content-preview-heading">
        <span aria-live="polite">{copyState === 'copied' ? '已复制' : copyState === 'failed' ? '复制失败，请选择文本后手动复制' : '完整内容'}</span>
        <Button type="text" size="small" aria-label="复制完整内容" icon={copyState === 'copied' ? <CheckOutlined /> : <CopyOutlined />}
          onClick={() => { void copyText(text).then(() => setCopyState('copied'), () => setCopyState('failed')); }} />
      </div>
      <div className="content-preview-text" tabIndex={0}>{text}</div>
    </div>}>
    {children}
  </Popover>;
}

function PreviewField({ value, children }: { value: unknown; children: ReactElement }) {
  const field = useRef<HTMLDivElement>(null);
  return <ContentPreview text={typeof value === 'string' ? value : ''} isOverflowing={() => {
    const element = field.current?.querySelector('input, textarea');
    return Boolean(element && (element.scrollWidth > element.clientWidth || element.scrollHeight > element.clientHeight));
  }}>
    <div ref={field} className="preview-field">{children}</div>
  </ContentPreview>;
}

export function PreviewInput(props: ComponentProps<typeof Input>) {
  return <PreviewField value={props.value ?? props.defaultValue}><Input {...props} /></PreviewField>;
}

export function PreviewTextArea(props: ComponentProps<typeof Input.TextArea>) {
  return <PreviewField value={props.value ?? props.defaultValue}><Input.TextArea {...props} /></PreviewField>;
}
