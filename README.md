# AegisTrace

AegisTrace is a private desk for a support team. A staff member asks a question. The system answers only from the team’s own documents. If the next step is to open a support ticket, a second person must approve it first. Nothing is written until that person says yes, and the same ticket is never created twice.

It is not a public website, and it is not a chat app. Visitors on a company website do not log in here. Staff do.

## Who it is for

| Person | What they do |
|---|---|
| Operator | Types the customer’s question and watches the answer |
| Reviewer | Reads a proposed ticket and approves or rejects it |
| Developer | Loads the documents and sets up the assistant |
| Admin | Creates the people above and looks after the workspace |

One company or team uses one workspace. Each person sees only the screens their job allows.

## Why it exists

A normal chatbot can sound sure and still be wrong, and it can take an action with no record. AegisTrace keeps three rules:

1. The answer must come from documents the team uploaded.
2. If those documents do not contain enough evidence, the system says so instead of guessing.
3. Opening a ticket is an action. A person approves it. The system keeps a record of who asked, who approved, and what was created.

## A normal day, with an example

Imagine a shop website. The returns page says an unused item can be returned within 30 days with the receipt. That page stays a normal page. AegisTrace is the desk behind it.

1. An admin creates an operator and a reviewer.
2. A developer uploads the returns policy.
3. A customer asks, “Can I return shoes I bought 12 days ago?”
4. The operator pastes that question into **Support run**.
5. The system searches the policy and answers from it.
6. If a ticket would help, the system stops and waits. It does not open the ticket yet.
7. The reviewer reads the proposed ticket and approves it.
8. One ticket is created. Approving the same request again does not create another ticket.

The sample question already loaded for a demo is: “Why was my application rejected, and what should I do before reapplying?” The sample policy says the person must wait 14 days, fix the items named in the rejection, and include the previous reference.

## What you see after signing in

- **Operations** — how many requests are waiting, finished, or failed.
- **Support run** — where an operator asks a question and follows the result.
- **Approvals** — where a reviewer allows or refuses a ticket.
- **Knowledge** — the documents the answers come from.
- **Agents** — the assistant setup: which documents it may use and which actions it may suggest.
- **Evaluation** — checks that answers stay tied to the documents.
- **Safety** — a view of blocked actions, approvals, and cases where the system refused to guess.
- **Observability** — the trail of a request, for people who need to inspect it.
- **Audit** — the record of important actions.
- **Administration** — users and workspace settings. Operators cannot open this.

## Try it on your computer

You need [Docker Desktop](https://www.docker.com/products/docker-desktop/). In this project folder, run:

```text
docker compose up --build
```

The first start takes several minutes because it builds the application. When it is ready, open:

http://localhost:3000/login

These accounts exist only in this local demo. They are not for a shared or public server.

| Email | Job |
|---|---|
| dev.operator@aegistrace.local | Operator |
| dev.reviewer@aegistrace.local | Reviewer |
| dev.developer@aegistrace.local | Developer |
| dev.admin@aegistrace.local | Admin |

Password for all four: `change-me-dev-password`

A short click-by-click demo is in `docs/demo-script.md`.

## Use your own documents instead of the sample

The sample people, the “Support” workspace, and the sample policy are there so the demo has something to click. They are not required for the product.

1. Sign in as the admin and create the real operator, reviewer, and developer.
2. Sign in as the developer, create a knowledge base, and upload your own policy as a text, Markdown, or PDF file.
3. Create an assistant that may search those documents and may only *suggest* a support ticket.
4. Turn that assistant on.
5. Sign in as the operator and ask a question a real customer would ask.

From then on, answers come from your file, not from the sample.

## What the system will not do

- It will not invent an answer when the documents do not support one.
- It will not open a ticket because a document says “ignore the rules.”
- It will not allow an urgent or critical ticket. A high-priority ticket needs an admin. A normal ticket needs a reviewer.
- It will not create a second ticket if the same approval is sent again.
- It does not replace the public website. The website is where the customer is. This desk is where the staff work.

## Run it on your own server

Another person can start a private copy with Docker on a Linux machine. That copy does not load the sample accounts. The steps are in `docs/deployment.md`. Putting this on a cloud provider is outside the current project. The notes under `infrastructure/aws` are a sketch only. They are not in use.

## For people who build or review the software

The browser talks to a Java service. That service stores the work in PostgreSQL, asks a Python component to read the documents and draft an answer, and asks a separate Python worker to create the ticket only after approval. Local file storage is Garage, an S3-compatible store that runs on the same machine. Redis shares limits when more than one copy of the service is running.

The default answers do not call a paid model. They copy the supporting sentences from the uploaded documents. A paid model can be connected later. It can change the wording. It still cannot approve a ticket.

More detail:

| Topic | Document |
|---|---|
| How a request moves from question to ticket | `docs/run-state-machine.md` |
| Who may approve | `docs/approval-state-machine.md` |
| What the system deliberately does not do | `docs/non-goals.md` |
| Security notes | `docs/threat-model.md` |
| How to run the checks | `docs/ci-cd.md` |

API notes are in `docs/api-boundary.md`. After the local stack is running, technical API pages are at http://localhost:8080/swagger-ui.
