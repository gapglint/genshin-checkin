#!/usr/bin/env python3
"""
Google Play 개발자 API로 출시를 다룬다. GitHub Actions(play.yml)에서만 돈다.
서비스 계정 키는 GitHub Secrets의 PLAY_SERVICE_ACCOUNT_JSON(환경 변수)로만 받고, 어디에도 출력하지 않는다.

  python3 tools/play.py check                      # 권한 확인: 트랙 목록만 읽고 아무것도 바꾸지 않는다
  python3 tools/play.py listing                    # 스토어 등록정보(언어별 제목·설명)와 이미지 주소를 읽기만 한다
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
    # 키 내용은 절대 출력하지 않는다. 잘못 넣었을 때는 길이·항목 이름·형식만 알려 준다.
    try:
        info = json.loads(raw)
    except json.JSONDecodeError as e:
        first = json.JSONDecoder().raw_decode(raw.strip())[0] if raw.strip().startswith("{") else None
        names = sorted(first.keys()) if isinstance(first, dict) else []
        sys.exit(f"시크릿이 JSON 하나가 아니에요({len(raw)}자, {e.msg} @ {e.pos}). 첫 부분의 항목 이름: {names}. "
                 "Google Cloud에서 받은 서비스 계정 키 파일(.json) 내용 전체를 그대로 붙여 넣어 주세요.")
    if not isinstance(info, dict) or info.get("type") != "service_account" or "private_key" not in info:
        names = sorted(info.keys()) if isinstance(info, dict) else []
        sys.exit(f"서비스 계정 키가 아니에요(항목 이름: {names}). type이 service_account이고 private_key가 있는 파일이어야 해요.")
    creds = service_account.Credentials.from_service_account_info(info, scopes=["https://www.googleapis.com/auth/androidpublisher"])
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


def listing():
    edits = service()
    edit = edits.insert(packageName=PACKAGE, body={}).execute()
    try:
        for l in edits.listings().list(packageName=PACKAGE, editId=edit["id"]).execute().get("listings", []):
            lang = l["language"]
            print(f"===== {lang}")
            print(f"[title] {l.get('title', '')}")
            print(f"[short] {l.get('shortDescription', '')}")
            print(f"[full]\n{l.get('fullDescription', '')}")
            for kind in ("icon", "featureGraphic", "phoneScreenshots", "sevenInchScreenshots", "tenInchScreenshots"):
                images = edits.images().list(packageName=PACKAGE, editId=edit["id"], language=lang, imageType=kind).execute().get("images", [])
                for i, img in enumerate(images):
                    print(f"[image] {kind} {i + 1} {img.get('url', '')}")
    finally:
        edits.delete(packageName=PACKAGE, editId=edit["id"]).execute()


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
    elif len(sys.argv) >= 2 and sys.argv[1] == "listing":
        listing()
    elif len(sys.argv) == 5 and sys.argv[1] == "upload":
        upload(sys.argv[2], sys.argv[3], sys.argv[4])
    else:
        sys.exit(__doc__)
