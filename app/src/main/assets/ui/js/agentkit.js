// Agent kit: image rules without T2I, and a set of skills written into the Linux home on every start.
// The app text of AGENTS.md is produced by settings.js, so it is corrected on its way to disk.
(function () {
const O = window.OCM;
const MARK = 'OCM-AGENTKIT-2';
const MEDIA = '/root/.cache/opencode-mob/media';

const SECTION5 = `## 5. Images & Attachments <!-- ${MARK} -->
- A user message may end with a block \`Attached files (paths on the phone, content not included):\`. Each line is an absolute path. Read the files yourself with your tools. Never ask the user to upload or paste them again.
- You create a picture only when the user clearly asks for one, and never as decoration of an ordinary answer.
- You are a text model. If you cannot generate images yourself, say so in one sentence and offer to draw a detailed picture with code instead. Do not call any image-generation service or helper.
- To draw: write code (Python with Pillow, or an SVG converted with \`rsvg-convert\`), save a PNG under \`${MEDIA}/\`, check that the file exists, then show it with \`![description](/absolute/path/file.png)\`.`;

const SEC2 = `## 2. Android apps
Use the \`launch-android-app\` skill when the user asks to open an app.`;
const SEC3 = `## 3. Web preview
Use the \`web-preview\` skill to show a web project inside the app.`;
const SEC6 = `## 6. Commands
Use the \`command-timeout\` skill for every slow or risky command. Never wait silently.`;
const SEC7 = `## 7. Skills
Skills are in \`~/.config/opencode/skills/\`. Load the one that fits the task: image-output, attached-files, command-timeout, debug-failure, project-scan, git-hygiene, background-jobs, verify-before-done, launch-android-app, web-preview. Do not print line statistics; the app counts them from your edits.`;

function patchRules(t) {
  if (typeof t !== 'string' || t.includes(MARK)) return t;
  let o = t.replace(/## 5\. Images & Attachments[\s\S]*?(?=\n## 6\.)/, SECTION5 + '\n');
  o = o.replace(/## 2\. Core Skill: Launching Android Applications[\s\S]*?(?=\n## 3\.)/, SEC2 + '\n');
  o = o.replace(/## 3\. Core Skill: In-App Web Project Preview[\s\S]*?(?=\n## 4\.)/, SEC3 + '\n');
  o = o.replace(/## 6\. Command Execution & Timeouts[\s\S]*?(?=\n## 7\.)/, SEC6 + '\n');
  o = o.replace(/## 7\. Diff Statistics Reporting[\s\S]*$/, SEC7 + '\n');
  return o;
}
if (O.native && O.native.writeText && !O.native._agentkit) {
  const w0 = O.native.writeText;
  O.native.writeText = (p, t) => w0(p, /\/(AGENTS|README)\.md$/.test(String(p)) ? patchRules(t) : t);
  O.native._agentkit = true;
}

const SKILLS = {
  'image-output': `---
name: image-output
description: Make a picture only when asked. A text model says it cannot generate images and offers to draw one with code (Pillow or SVG to PNG).
---

# Image output

1. Create nothing unless the user asked for a picture, chart, diagram, logo or screenshot.
2. If you cannot generate images natively, say that in one sentence and offer a detailed drawing made with code.
3. Pillow: \`from PIL import Image, ImageDraw, ImageFont\`; font \`/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf\`.
4. SVG: write \`in.svg\`, convert with \`rsvg-convert -w 1280 in.svg -o out.png\`.
5. Save to \`${MEDIA}/name.png\`, check the file is not empty, show it with \`![description](${MEDIA}/name.png)\`.
6. One picture per request. Do not add pictures to ordinary replies.
`,
  'attached-files': `---
name: attached-files
description: Work with files the user attached by path. The message holds only absolute paths, never the content.
---

# Attached files

- The block \`Attached files (paths on the phone, content not included):\` lists absolute paths.
- Look first: \`ls -la <path>\`, \`file <path>\`. For archives use \`unzip -l\` or \`tar -tf\` before extracting anything.
- Read big files in parts (\`head -c\`, \`sed -n\`, \`rg\`), not whole.
- Shared storage may be read-only for you. Copy to \`/root/work/\` before changing anything and tell the user where the result is.
- If a path does not exist, say so. Do not guess its content.
`,
  'command-timeout': `---
name: command-timeout
description: Run shell commands so a hang or a failure is always visible. Use timeouts, check exit codes, never wait silently.
---

# Command timeout

- Wrap anything that can be slow: \`timeout 120 <cmd>\` (up to 600 for builds the user asked for).
- Exit code 124 means timeout, 137 means killed. Report it and change the approach instead of repeating the same call.
- Never start interactive programs (vim, nano, top, less, REPLs, \`npm init\` without \`-y\`). Use non-interactive flags.
- Servers and watchers: \`nohup <cmd> > /root/job.log 2>&1 &\`, print the PID, check the port with \`timeout 5 curl -sI localhost:PORT\`.
- No output for a long time: look at \`ps aux\`, the log file, then kill the process and say what you tried.
- A \`[watchdog]\` message from the app means a run was silent for 8 minutes. Check the last command first.
`,
  'debug-failure': `---
name: debug-failure
description: Find the cause of a failing command, test or build in a fixed order before changing code.
---

# Debug a failure

1. Read the whole error, not the last line. Copy the first real error, not the cascade after it.
2. Reproduce with the smallest command. Note the exit code.
3. Check the plain causes: missing file, wrong directory, missing dependency, wrong version (\`--version\`), permissions, no disk space (\`df -h\`).
4. Change one thing at a time and rerun the same command.
5. After the fix run the original command once more and state the result.
`,
  'project-scan': `---
name: project-scan
description: Get oriented in an unfamiliar repository in a minute before editing anything.
---

# Project scan

- \`ls -la\`, \`git status -s | head\`, \`git log --oneline | head -5\`.
- Read README and the manifest (\`package.json\`, \`pyproject.toml\`, \`go.mod\`, \`build.gradle*\`).
- Find entry points and tests: \`rg --files | head -50\`, \`rg -n -i 'todo|fixme' | head\`.
- Find the build and test commands before changing code, and run them once to get a baseline.
`,
  'git-hygiene': `---
name: git-hygiene
description: Safe git use for the agent. Look before changing, commit small, never rewrite shared history.
---

# Git hygiene

- Before editing: \`git status -s\`. Do not mix your changes with unrelated uncommitted work.
- Commit only on request. One logical change per commit, message in the imperative.
- Never force-push, never \`reset --hard\`, never delete branches unless the user asked for exactly that.
- Never commit secrets (.env, keys, tokens). Check with \`git diff --staged\` first.
`,
  'background-jobs': `---
name: background-jobs
description: Start, watch and stop long-running processes (dev servers, watchers, downloads) without blocking the session.
---

# Background jobs

- Start: \`setsid nohup <cmd> > /root/jobs/<name>.log 2>&1 & echo $!\`.
- Watch: \`tail -n 30 /root/jobs/<name>.log\`, \`ps -p <pid>\`, port check with \`timeout 5 curl -sI localhost:PORT\`.
- Stop: \`kill <pid>\`, then \`kill -9 <pid>\` after 5 seconds if it is still there.
- Say the PID, the log path and the port to the user in the reply.
`,
  'launch-android-app': `---
name: launch-android-app
description: Open or switch to an installed Android app or a system setting when the user asks for it.
---

# Launch an Android app

1. Run \`openapp <app_name_or_package>\` in the shell.
2. Tell the user the app is opening and add a button: \`<button data-action="open_app" data-target="<app_name>">Open <app_name></button>\`.
3. If \`openapp\` prints an error, say so and name the package you tried. Do not retry blindly.
4. Shared storage (\`/sdcard\`) is mounted noexec: run scripts with an explicit interpreter.
`,
  'web-preview': `---
name: web-preview
description: Show a web project inside the app: start the preview from the shell or offer a button.
---

# Web preview

- From the shell: \`openpreview <path-to-index.html-or-folder>\`.
- In the reply you may add: \`<button data-action="preview" data-path="index.html">Open site</button>\`.
- Other buttons the chat understands: \`new_chat\`, \`files\`, \`term\`.
- For a dev server, start it in the background (see the background-jobs skill) and preview \`http://localhost:PORT\`.
`,
  'verify-before-done': `---
name: verify-before-done
description: Never report a task as done before the code was built or run and the result was read.
---

# Verify before done

- Run the build, the tests or the program that proves the change. Read the output.
- If something cannot be run here, say exactly what was not verified.
- State what changed in files in a few lines, with paths. Do not claim success from reading the code alone.
`
};

const DIRS = ['/root/.config/opencode/skills', '/root/.opencode/skills'];
function writeSkills() {
  if (!O.native || !O.native.writeText) return;
  for (const slug of Object.keys(SKILLS)) {
    for (const d of DIRS) {
      try { O.native.writeText(d + '/' + slug + '/SKILL.md', SKILLS[slug]); } catch (_) {}
    }
  }
}

// The t2i helper of older runtimes is replaced by a stub that explains the new behaviour.
const STUB = `#!/usr/bin/env python3
# ocm-t2i-stub
import sys
print("ERROR: image generation helper is removed. Do not retry. If you cannot generate images yourself, say so and offer to draw the picture with code (Pillow or SVG to PNG).")
sys.exit(2)
`;
function stubT2i() {
  if (!O.native || !O.native.readText) return;
  for (const p of ['/usr/local/bin/t2i', '/usr/bin/t2i', '/root/bin/t2i']) {
    try {
      const r = O.native.readText(p);
      if (r && r.ok && typeof r.text === 'string' && !r.text.includes('ocm-t2i-stub')) O.native.writeText(p, STUB);
    } catch (_) {}
  }
}

let done = false;
function run() {
  if (done) return;
  done = true;
  writeSkills();
  stubT2i();
  try { if (O.updateReadmeForModel) O.updateReadmeForModel(); } catch (_) {}
}
// The runtime must be up so that the home exists.
if (O.on) O.on('ready', run);
setTimeout(() => { if (O.S && O.S.up) run(); }, 4000);
const iv = setInterval(() => { if (O.S && O.S.up) { clearInterval(iv); run(); } }, 2000);
})();
