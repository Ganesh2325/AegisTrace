# UX audit

Inspected in the browser on 2026-10-05 while signed in as Operator: sign-in, Operations, Audit, and Knowledge. Other routes were read from the page source. Nothing was redesigned.

## Visual system

The app is a dark canvas (`#12161c`) with panel cards (`#1c232d`), a hairline border, mist labels, and one amber primary button. Type is Segoe UI, with Cascadia Code or Consolas for numbers. Kickers are small uppercase tracked labels. Focus rings use amber. That system is consistent and thin: there are no shared badges, no icon set, no dialog, no toast, and no skeleton component beyond a pulsing rectangle.

## Spacing and hierarchy

The console is a 220px sidebar plus padded main column. On the Operations page the hierarchy is clear: kicker, title, one sentence, then a 12-card grid, then one action. The cards are equal weight. A 6 and a 904906 ms number get the same visual rank, so the eye does not know which figure is a count and which is a duration.

The sign-in page is a single narrow column on a large empty canvas. The primary button is full width and obvious. The helper text correctly says the password is a development credential.

## Navigation

Ten links, one flat list: Dashboard, Support run, Approvals, Agents, Knowledge, Evaluation, Observability, Audit, Admin, Why this exists. The current route gets a light fill. There is no grouping, no workspace name beyond the seed, no environment pill, and no search.

Every role sees every link. As Operator, Audit stayed in the sidebar, the page title rendered, and the body said “Your role cannot perform this action.” The empty When / Action / Resource headers still appeared under the error.

“Why this exists” leaves the shell because `/why` is outside the console layout.

A full page load shows “Checking session…” before the sidebar returns. That flash happened on Audit and Knowledge.

The sidebar identity block shows the display name and the role in mono, then Log out. There is no menu.

## Dashboard

The grid matches the API exactly, including the large millisecond figures. There is no chart, no recent-run list, and no pending-approval list. “New support run” is the only click that starts work. Cards are not links.

Loading is a single gray pulse. An error is one rose sentence. Zeros would render as zeros. There is no empty-workspace illustration because the copy already says an empty workspace shows zeros.

## Tables and lists

Knowledge is a real table: 11 document titles, status text `ACTIVE`, and chunk counts. Rows are not clickable. There is no upload control, filter, or status badge. Column headers are mist-colored and easy to miss against the dark page.

Audit uses the same bare table. Approvals, agents, and evaluations are stacked panels, not tables. Approval arguments are a JSON `<pre>` block.

## Forms

Sign-in has labeled email and password fields. Support run is a question form with the demo question prefilled. Admin has a policy dry-run form. Settings are displayed as JSON and cannot be edited on the page.

Approval decisions do not ask for a reason. The client sends “Reviewed” or “Declined”.

## States

| State | What the UI does |
|---|---|
| Loading | “Checking session…”, or a pulsing block on the dashboard and run detail |
| Empty | Short mist sentence on approvals, evaluations, and knowledge |
| Error | Rose text. Audit still shows the empty table |
| Success | Moss text on the approval notice and on a passed evaluation |
| Running | Run detail shows the state name and “Working…” until an answer exists |

Status color is not a system. `ACTIVE`, `PENDING`, `EXPIRED`, and `TIMED_OUT` do not share one visual language.

## Responsiveness and accessibility

The sidebar becomes a horizontal scrolling row under the `md` breakpoint. The metric grid goes from one column to two to four. That is the extent of the responsive work.

Buttons and links have a visible focus outline. The login error uses `role="alert"`. Most other errors do not. Tables have no caption. Metric values are not associated with their labels for assistive tech beyond reading order. Color contrast of mist-on-ink for kickers is the weakest text on the page.

## Consistency

Page titles, kickers, panels, and the amber button repeat. After that, each page invents its own density: the dashboard is cards, knowledge is a table, observability is a JSON dump, admin is a list plus a form. Run detail is the only two-column layout.

The large latency numbers are a comprehension problem, not a CSS problem. They are real wall-clock milliseconds, printed without a word that says they include human wait.
