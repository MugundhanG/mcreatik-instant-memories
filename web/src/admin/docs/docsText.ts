/** Minimal shape of the syntax-tree nodes react-markdown passes to components. */
export interface TreeNode {
  type: string
  value?: string
  tagName?: string
  properties?: { className?: unknown }
  children?: TreeNode[]
}

/** Plain text inside a node, e.g. a heading's words without formatting. */
export function textOf(node: TreeNode | undefined): string {
  if (!node) return ''
  if (node.type === 'text') return node.value ?? ''
  return (node.children ?? []).map(textOf).join('')
}

/** Heading text → URL-safe id, used for "#" links and the "On this page" list. */
export function slugify(text: string): string {
  return text
    .toLowerCase()
    .replace(/[`*_]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
}
