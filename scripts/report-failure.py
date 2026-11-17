#!/usr/bin/env python3
import os
import urllib.request
import json
import sys

def main():
    log_file = "gradle-build.log"
    if not os.path.exists(log_file):
        print("No gradle-build.log found.")
        return

    with open(log_file, "r", encoding="utf-8", errors="replace") as f:
        lines = f.readlines()

    tail_lines = lines[-120:] if len(lines) > 120 else lines
    err_text = "".join(tail_lines)

    summary_file = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_file:
        try:
            with open(summary_file, "a", encoding="utf-8") as sf:
                sf.write("### ❌ Gradle Build Failed\n\n```\n" + err_text + "\n```\n")
        except Exception as e:
            print(f"Error writing step summary: {e}")

    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    repo = os.environ.get("GITHUB_REPOSITORY")
    sha = os.environ.get("GITHUB_SHA")

    if not token or not repo or not sha:
        print("Missing token, repo or sha for commit comment.")
        return

    comment_url = f"https://api.github.com/repos/{repo}/commits/{sha}/comments"
    body_payload = {
        "body": f"### ❌ Сборка APK завершилась с ошибкой компилятора:\n\n```\n{err_text}\n```"
    }
    req = urllib.request.Request(
        comment_url,
        data=json.dumps(body_payload).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "CI-Error-Reporter/1.0",
            "Content-Type": "application/json"
        }
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            print("Successfully posted failure comment to commit!")
    except Exception as e:
        print(f"Failed to post commit comment: {e}")

if __name__ == "__main__":
    main()
