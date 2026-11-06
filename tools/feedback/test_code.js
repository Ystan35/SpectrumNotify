// node tools/feedback/test_code.js : checks the parts of Code.gs that do not need Google or GitHub.
const assert = require("assert");
const fs = require("fs"), path = require("path"), vm = require("vm");
const src = fs.readFileSync(path.join(__dirname, "Code.gs"), "utf8");
const m = { exports: {} };
vm.runInNewContext(src, { module: m, console });
const { validate, redact } = m.exports;
const statusOf = (i, c) => JSON.parse(JSON.stringify(m.exports.statusOf(i, c)));  // plain objects: the vm context has its own Object

const ok = { ticket: "SN-7F3K", kind: "bug", message: "Join class opens attendance", install: "a1b2c3d4e5f60718" };
assert.strictEqual(validate(ok), null);
assert.ok(validate({ ...ok, ticket: "7F3K" }));
assert.ok(validate({ ...ok, kind: "spam" }));
assert.ok(validate({ ...ok, message: "   " }));
assert.ok(validate({ ...ok, message: "x".repeat(2001) }));
assert.ok(validate({ ...ok, install: "../../etc" }));

assert.strictEqual(redact("my password: hunter2 pls"), "my password: [removed] pls");
assert.strictEqual(redact("token=abc"), "token: [removed]");
assert.ok(!redact("here eyJhbGciOiJIUzI1NiJ9abcdefghijklmnopqrstuvwxyz0123 end").includes("eyJ"));
assert.strictEqual(redact("WIX1001 lecture at 10am"), "WIX1001 lecture at 10am");

assert.deepStrictEqual(statusOf({ state: "open", labels: [] }, []), { status: "received" });
assert.deepStrictEqual(statusOf({ state: "open", labels: [{ name: "in-progress" }] }, []), { status: "working" });
assert.deepStrictEqual(statusOf({ state: "closed", state_reason: "completed", labels: [{ name: "bug" }, { name: "fixed-in:0.33.0" }] },
  [{ body: "looking" }, { body: "Reply: Fixed, thanks for reporting!" }]),
  { status: "fixed", version: "0.33.0", reply: "Fixed, thanks for reporting!" });
assert.deepStrictEqual(statusOf({ state: "closed", state_reason: "not_planned", labels: [] }, []), { status: "closed", reply: null });
console.log("feedback script checks pass");

// screenshots
const { imageType } = m.exports;
assert.strictEqual(imageType([-1, -40, -1, -32, 0]), "image/jpeg");          // signed bytes, as Apps Script gives them
assert.strictEqual(imageType([0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10, 0]), "image/png");
assert.strictEqual(imageType([0x3c, 0x73, 0x76, 0x67]), null);               // "<svg": not accepted
assert.strictEqual(validate({ ...ok, images: ["/9j/4AAQ"] }), null);
assert.ok(validate({ ...ok, images: ["a", "b", "c", "d"] }));
assert.ok(validate({ ...ok, images: ["<script>"] }));
assert.ok(validate({ ...ok, images: "notalist" }));
console.log("screenshot checks pass");
