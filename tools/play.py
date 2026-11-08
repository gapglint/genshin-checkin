#!/usr/bin/env python3
"""
Google Play 개발자 API로 출시를 다룬다. GitHub Actions(play.yml)에서만 돈다.
서비스 계정 키는 GitHub Secrets의 PLAY_SERVICE_ACCOUNT_JSON(환경 변수)로만 받고, 어디에도 출력하지 않는다.

  python3 tools/play.py check                      # 권한 확인: 트랙 목록만 읽고 아무것도 바꾸지 않는다
  python3 tools/play.py upload AAB TRACK STATUS    # AAB를 트랙에 올린다. STATUS: draft(콘솔에서 직접 출시) | completed
출시 노트는 store/whatsnew/<언어>.txt(ko-KR, en-US, ja-JP …)에서 읽는다(Play 한도 500자).
"""
import json
import os
import sys
from pathlib import Path

from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

PACKAGE = "kr.sumi.checkin"
NOTES = Path(__file__).resolve().parent.parent / "store" / "whatsnew"


def service():
    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "")
    if not raw.strip():
        sys.exit("PLAY_SERVICE_ACCOUNT_JSON 시크릿이 없어요.")
    creds = service_account.Credentials.from_service_account_info(json.loads(raw), scopes=["https://www.googleapis.com/auth/androidpublisher"])
    return build("androidpublisher", "v3", credentials=creds, cache_discovery=False).edits()


def notes():
    out = []
    for f in sorted(NOTES.glob("*.txt")):
        text = f.read_text(encoding="utf-8").strip()
        if len(text) > 500:
            sys.exit(f"{f.name} 출시 노트가 500자를 넘어요({len(text)}자).")
        out.append({"language": f.stem, "text": text})
    return out


def check():
    edits = service()
    edit = edits.insert(packageName=PACKAGE, body={}).execute()
    try:
        tracks = edits.tracks().list(packageName=PACKAGE, editId=edit["id"]).execute().get("tracks", [])
        for t in tracks:
            versions = [f"{r.get('name', '')} {r.get('versionCodes', [])} {r.get('status', '')}" for r in t.get("releases", [])]
            print(f"{t['track']}: {'; '.join(versions) or '-'}")
        print("연결 확인: 이 앱을 읽을 수 있어요.")
    finally:
        edits.delete(packageName=PACKAGE, editId=edit["id"]).execute()  # 확인만 하고 버린다


def upload(aab, track, status):
    if status not in ("draft", "completed"):
        sys.exit("STATUS는 draft 또는 completed")
    edits = service()
    edit = edits.insert(packageName=PACKAGE, body={}).execute()
    bundle = edits.bundles().upload(packageName=PACKAGE, editId=edit["id"],
                                    media_body=MediaFileUpload(aab, mimetype="application/octet-stream", resumable=True)).execute()
    code = bundle["versionCode"]
    release = {"versionCodes": [str(code)], "status": status, "releaseNotes": notes()}
    edits.tracks().update(packageName=PACKAGE, editId=edit["id"], track=track, body={"track": track, "releases": [release]}).execute()
    edits.commit(packageName=PACKAGE, editId=edit["id"]).execute()
    print(f"{track} 트랙에 {code}를 올렸어요({'초안: 콘솔에서 출시를 눌러 주세요' if status == 'draft' else '출시'}).")


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "check":
        check()
    elif len(sys.argv) == 5 and sys.argv[1] == "upload":
        upload(sys.argv[2], sys.argv[3], sys.argv[4])
    else:
        sys.exit(__doc__)
