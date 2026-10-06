# AegisTrace design system

This is the visual and interaction foundation for the console. Later phases should consume these tokens and components. They should not invent a second palette, button, or status treatment.

The interface stays dark. Tokens are CSS variables, so a future theme can replace values without rewriting components.

## Color

Tokens live in `frontend/app/globals.css` as RGB channel triples and are exposed to Tailwind as `rgb(var(--token) / <alpha-value>)`. That is what makes `bg-accent/90` and `bg-danger/10` valid. The hex comments in the stylesheet are the same colors.

| Token | CSS variable | Tailwind | Role |
| --- | --- | --- | --- |
| background | `--canvas` | `bg-canvas` | Application background |
| surface | `--surface` | `bg-surface` | Sidebar and table header |
| surface elevated | `--elevated` | `bg-elevated` | Cards and inputs sit above the canvas |
| overlay | `--overlay` | `bg-overlay` | Tooltips, dialogs, drawers |
| border subtle | `--line` | `border-line` | Default divider |
| border strong | `--line-strong` | `border-line-strong` | Emphasized control border |
| text primary | `--paper` | `text-paper` | Titles and values |
| text secondary | `--muted` | `text-muted` | Descriptions and metadata |
| text muted | `--faint` | `text-faint` | Placeholders |
| accent | `--accent` | `bg-accent` / `text-accent` | One primary action |
| success | `--success` | `text-success` | Completed, approved, active |
| warning | `--warning` | `text-warning` | Attention, approval required, high latency |
| danger | `--danger` | `text-danger` | Failed, denied, destructive |
| info | `--info` | `text-info` | In progress, links |
| focus | accent outline | `outline-accent` | Keyboard focus |

Status surfaces use the same hues at low opacity (`bg-success/10`, `bg-warning/10`, `bg-danger/10`, `bg-info/10`). Do not put raw hex values in page components.

Legacy class names (`ink`, `panel`, `mist`, `amber`, `moss`, `rose`, `sky`, `.btn`, `.field`, `.panel`) point at the same variables so older markup still resolves.

## Typography

Use the page and section headers. Do not size internal console titles like a marketing hero.

| Role | Treatment |
| --- | --- |
| Eyebrow | 11px, medium, uppercase, tracking, `text-muted` |
| Page title | `text-xl font-semibold` |
| Section title | `text-sm font-medium` |
| Card title | `text-base font-medium` |
| Body | `text-sm leading-5` or `leading-6` |
| Secondary | `text-sm text-muted` |
| Metadata | `text-xs text-muted` |
| Caption | 11px uppercase `text-muted` |
| Code / IDs | `font-mono text-xs` via `Mono` or `CodeBlock` |
| Metric value | `font-mono text-[1.65rem]` inside `MetricCard` |
| Button / label | `text-sm font-medium` |

`PageHeader` is the only `h1` on a console page. `SectionHeader` is the `h2` for a group.

## Spacing

Use Tailwind's 4px scale. Prefer these steps: 1 (4), 2 (8), 3 (12), 4 (16), 5 (20), 6 (24), 8 (32), 10 (40), 12 (48), 16 (64), 20 (80).

Page gutters are `p-4` and `md:p-6` on the shell. Vertical rhythm inside a page is `space-y-5` from `Page`.

## Width

`Page` sets the content width. Do not wrap every screen in the same max width.

| Variant | Width | Use |
| --- | --- | --- |
| `focus` | `max-w-2xl` | Support run |
| `standard` | `max-w-3xl` | Agents, Admin, configuration |
| `wide` | 72rem | Operations, tables, approvals |
| `full` | none | Run detail |

## Radius, border, shadow

Radius: `sm` 4px, `md` 6px (controls and cards), `lg` 10px, `full` for status and risk pills.

Borders: `border-line` by default, `border-line-strong` for secondary buttons, semantic color at 40% opacity for warning, danger, and success cards.

Shadow: `shadow-panel` only on overlays (dialog, drawer, tooltip, toast). Cards are flat.

## Density

| Density | Where | How |
| --- | --- | --- |
| Compact | Tables, audit, traces | `DataTable` default, `py-2` cells, `Card` `compact` |
| Default | Dashboard cards | `p-4`, metric min height 7.25rem |
| Comfortable | Support question and configuration forms | `space-y-4` between fields, `min-h-32` textarea |

## Surfaces

Layers, from back to front: canvas, surface, elevated, overlay. A section is not a card unless grouping helps. Operations uses one grid of metric cards. It does not wrap that grid in another card.

## Buttons

`Button` variants: `primary`, `secondary`, `tertiary`, `ghost`, `danger`. `ButtonLink` uses the same classes. `IconButton` is the square icon control and requires `label` for its accessible name.

One primary button per region. Danger is for reject and other destructive actions. Ghost is for low-emphasis actions such as logout, cancel, and activate.

States: hover and active are color shifts, disabled lowers opacity and sets `cursor-not-allowed`, loading sets `disabled` and `aria-busy` and replaces the label (for example `Starting…`). A loading button cannot be submitted twice.

### When to use

The primary action of a form or queue decision.

### When not to use

Navigation that is a text link. A second competing primary in the same region. Orange styling on every control.

## Forms

`Label`, `HelpText`, `FormError`, `TextField`, `TextAreaField`, `SelectField`, `CheckboxField`, `RadioField`, `SwitchField`, and `FieldGroup`.

Controls use a canvas fill, `border-line`, 6px radius, and the shared focus outline. Errors set `aria-invalid`, `role="alert"`, and a danger border. Disabled fields drop opacity.

### When to use

Any labeled console input.

### When not to use

Read-only metadata. A browser-default unstyled control next to these fields.

## Cards

`Card` variants: `default`, `interactive`, `elevated`, `warning`, `danger`, `success`, `compact`, `metric`.

`MetricCard` is the operations number: label, value, context, definition tooltip, optional note, and `default` or `warning` tone. High latency stays on the p95 card as warning plus the note “High latency observed”. A missing rate stays “No data”. Configured zero-price cost stays a currency amount with the context “Configured zero-price model”. It is not labeled as savings.

### When to use

A repeated record, a metric, or a form group that needs a boundary.

### When not to use

Wrapping an entire page, or turning every paragraph into a floating box.

## Tables

`DataTable` renders a header, rows, hover, optional selected row, compact or default density, empty, loading, and error. Optional `sortable` marks a column as not yet connected (`aria-disabled`). Optional `actions` is a row-action slot. Optional `pagination` is a footer slot. Neither slot sorts, filters, or pages by itself.

Cell patterns, composed from existing primitives:

| Cell | Use |
| --- | --- |
| Status | `StatusBadge` |
| Risk | `RiskBadge` |
| Timestamp | `Timestamp` |
| Duration | `DurationText` |
| User, agent, tool, environment | Text, with `Mono` when the value is an identifier |
| Cost | Formatted currency plus a pricing-status context |
| Action | `Button` or `TextLink` in the actions slot |

### When to use

Lists of documents, audit events, and later runs.

### When not to use

A single record. A dashboard of twelve numbers.

## Status

`StatusBadge` maps a known status to a label, an icon, and a tone. Color is never the only signal.

| Status | Label | Signal |
| --- | --- | --- |
| QUEUED, PENDING, EXPIRED | Queued, Pending, Expired | Clock |
| RUNNING, RETRIEVING, THINKING, TOOL_EXECUTING, PROCESSING | Running, Retrieving, Thinking, Executing, Processing | Dot, info |
| TOOL_PROPOSED, APPROVAL_REQUIRED, TIMED_OUT | Tool proposed, Approval required, Timed out | Alert or clock, warning |
| APPROVED, COMPLETED, ACTIVE | Approved, Completed, Active | Check |
| REJECTED, FAILED, DENIED | Rejected, Failed, Denied | Cross |
| CANCELLED, INACTIVE, NO_DATA | Cancelled, Inactive, No data | Muted |
| UNAVAILABLE | Unavailable | Alert, warning |

Unknown values render the raw text with a muted dot. Pass `label` only to change the visible word, as evaluations do with “passed” and “failed”. That override must not imply a human study.

## Risk

`RiskBadge` accepts `LOW`, `MEDIUM`, `HIGH`, and `CRITICAL`. Each has a visible level and a screen-reader meaning: low impact, recorded decision, administrator, or blocked by policy. Any other string renders as “Risk {value}” and is not restyled into a known level. Do not invent a risk when the API did not send one.

### When to use

A stored proposal risk.

### When not to use

A decorative severity on a metric that has no risk field.

## Help

`HelpTooltip` is a button labeled with the definition, toggled by click, hover, and Escape. The tooltip has `role="tooltip"`. Use it for a metric definition that does not fit on the card. Do not hide a required warning inside it. High latency is visible on the card.

## Loading, empty, error, unavailable

| Component | Meaning |
| --- | --- |
| `Skeleton` | Content shape is known and data is loading |
| `PageLoading` | Session check before the shell |
| `Button` `loading` | The clicked action is in flight |
| `EmptyState` | The request succeeded and the collection is empty |
| `ErrorState` | The request failed. Optional Retry. `role="alert"` |
| `UnavailableState` | The dependency could not be reached. This is not a zero |

Do not render `0` or `0%` for an unknown metric. Statuses `NO_DATA` and `PRICING_UNAVAILABLE` stay visible as words.

### When not to use

A spinner on every card. A progress bar that is not backed by run events. An empty illustration.

## Toast

`ToastProvider` and `useToast(tone, text)` show success, info, warning, and error for about four seconds in an `aria-live="polite"` region. Approval decisions also keep a persistent line on the page. Do not use a toast as the only record of a security decision.

## Dialog and drawer

`Dialog` supports title, description, body, close, confirm, danger, loading, and error. Escape and backdrop click close it. Tab wraps inside the dialog and focus returns to the previous element.

`Drawer` is the same overlay pattern, anchored to the right, for a future detail pane. It is not wired to a business workflow in this phase.

Open both without entrance animation.

### When not to use

Information that can sit on the page. A confirmation for a non-destructive navigation.

## Timestamp and duration

`Timestamp` shows a relative time and puts the UTC timestamp on the element title, from the ISO value. It does not change how the API stores time.

`frontend/lib/duration.ts` is the only duration formatter. `DurationText` renders it and puts the exact millisecond count on the title. Operations latency and approval wait use that component. Context sentences call `formatDuration` from the same module.

## Technical text

`Mono` is for run ids, trace ids, model ids, roles, and versions. `CodeBlock` is for JSON and policy payloads. Do not set body copy in monospace.

## Links

`TextLink` variants: `primary`, `secondary`, `inline`, `external`, `nav`. External links open in a new tab with `rel="noreferrer"` and a visible ↗. Jaeger and Grafana use the external treatment. Sidebar links stay in `Shell` and set `aria-current="page"` on the active item. Their order is unchanged.

## Accessibility

Interactive controls use a native `button`, `a`, `input`, `textarea`, `select`, or `label`. Focus is a 2px accent outline with offset. Global CSS does not remove outlines. Status and risk include an icon or text, not color alone. Dialogs use `role="dialog"` and `aria-modal`. Error text uses `role="alert"`.

## Motion

Hover and active states use a 150ms color transition. Skeletons pulse. `prefers-reduced-motion: reduce` shortens animation and transition duration. Dialogs and drawers do not animate.

## Usage rules

- Read tokens. Do not copy hex into a page.
- One `h1` per page, from `PageHeader`.
- One primary button per region.
- Unknown numbers stay “No data” or “Unavailable”.
- Heuristic evaluation results say they are checks, not a benchmark.
- Configured zero-price cost is a configured price, not measured savings.
- A denied or rejected action uses danger treatment, not success.
