# Component inventory

Only components that exist in `frontend/components/ui` are listed. Status is READY when a current page uses the component, and PARTIAL when the primitive exists and no page workflow depends on it yet.

| Component | Purpose | Consumers | Variants | Accessibility | Status |
| --- | --- | --- | --- | --- | --- |
| `Button` | Action control with loading lock | Support run, approvals, agents, admin, login, run detail, error and unavailable retry | primary, secondary, tertiary, ghost, danger; loading; disabled | Native button, `aria-busy` while loading, visible focus, disabled | READY |
| `ButtonLink` | Navigation styled as a button | Operations, Why, empty states | Same variants as `Button` | Real link | READY |
| `IconButton` | Square icon action | None yet | Same variants; required `label` | `aria-label` is required | PARTIAL |
| `Icon` | Local SVG for status and spinners | `StatusBadge`, `Button` loading | dot, check, alert, x, clock, external, spinner | `aria-hidden` | READY |
| `TextField` | Single-line input | Login, Admin policy dry run | error, disabled, hint | Label, `aria-invalid`, error id | READY |
| `TextAreaField` | Multi-line input | Support run | error, hint | Label, `aria-invalid`, error id | READY |
| `SelectField` | Native select | None yet | labeled | Associated label | PARTIAL |
| `CheckboxField` | Checkbox row | None yet | labeled | Label wraps the input | PARTIAL |
| `RadioField` | Radio row | None yet | labeled | Label wraps the input | PARTIAL |
| `SwitchField` | Boolean switch | None yet | labeled | `role="switch"` | PARTIAL |
| `FieldGroup` | Grouped fields | Admin policy dry run | optional legend | `fieldset` and `legend` | READY |
| `Label`, `HelpText`, `FormError` | Field chrome | Used by the field components and Support run | — | Error has `role="alert"` | READY |
| `Card` | Surface for a record or form | Approvals, agents, evaluations, admin, login, run detail | default, interactive, elevated, warning, danger, success, compact, metric | `article` | READY |
| `MetricCard` | One operations number | Operations, twelve current metrics | default, warning; hint; note | Tooltip button on the definition | READY |
| `HelpTooltip` | Short definition | Operations metric cards | open, closed | Button, `aria-expanded`, `role="tooltip"`, Escape | READY |
| `PageHeader` | Eyebrow, title, description, actions, status | Operations, Support run, Approvals, Agents, Knowledge, Evaluation, Observability, Audit, Admin | — | Single `h1` | READY |
| `SectionHeader` | Group title | Run detail citations and timeline | optional action | `h2` | READY |
| `Page` | Width and vertical rhythm | All console pages above, plus run detail | standard, wide, full, focus | — | READY |
| `DataTable` | Table foundation | Knowledge, Audit | compact, default; loading; empty; error; selected; sortable placeholder; actions slot; pagination slot | Header cells, `aria-selected` when selected, `aria-busy` while loading | READY |
| `StatusBadge` | Named status | Agents, knowledge, evaluations, run detail | All statuses in `STATUSES` | Icon plus text | READY |
| `RiskBadge` | Stored risk level | Approvals | LOW, MEDIUM, HIGH, CRITICAL; unknown text fallback | Level text plus screen-reader meaning | READY |
| `EmptyState` | Successful empty collection | Approvals, agents, knowledge, evaluations, audit | optional action link | Heading and explanation | READY |
| `ErrorState` | Failed request | Operations, approvals, agents, knowledge, evaluations, audit, admin, run detail | optional Retry | `role="alert"` | READY |
| `UnavailableState` | Dependency not reached | Observability | optional Retry | `role="status"`; does not display zero | READY |
| `Skeleton` | Content placeholder | Operations, observability, knowledge, agents, evaluations, audit, admin, run detail | height class | `aria-hidden` | READY |
| `PageLoading` | Session shell placeholder | Shell | label | `aria-busy`, `aria-live` | READY |
| `ToastProvider`, `useToast` | Transient feedback | Shell hosts it; approvals push success or warning | success, info, warning, error | `aria-live="polite"` | READY |
| `Dialog` | Modal foundation | None yet | confirm, danger, loading, error | `role="dialog"`, `aria-modal`, focus trap, Escape, focus restore | PARTIAL |
| `Drawer` | Side panel foundation | None yet | title and body | `role="dialog"`, `aria-modal`, Escape | PARTIAL |
| `DurationText` | Duration plus exact milliseconds | Operations p50, p95, p99, approval wait | — | Exact value on `title` | READY |
| `Timestamp` | Relative time plus UTC title | Audit, run timeline fallback | — | `time` and `dateTime` | READY |
| `Mono` | Identifier text | Approvals, agents, knowledge, evaluations, audit, admin, run detail | — | Inherits surrounding text | READY |
| `CodeBlock` | JSON or payload | Approvals, evaluations, observability, admin | — | `pre` | READY |
| `TextLink` | Text link | Observability external tools, approval run id | primary, secondary, inline, external, nav | External sets `target` and `rel` | READY |

`frontend/lib/duration.ts` is the shared formatter behind `DurationText` and the approval-wait context sentence. There is no second formatter.

Sidebar link order and labels live in `frontend/components/Shell.tsx`. This inventory does not treat that list as a new navigation system.
