#!/usr/bin/env python3
"""
스토어 스크린샷용 게임 아이템 그림(원석·모라·경험책·광석 등)을 받아 store/item-art/에 둔다.
이 그림은 HoYoverse 저작물이라 저장소에 올리지 않는다(.gitignore). 스크린샷을 다시 만들 때만:
  python3 store/fetch_item_art.py && ./gradlew recordPaparazziDebug --tests '*StoreAssetsTest*'
호요랩 출석 페이지의 이번 달 보상 목록(로그인 없이 공개)에서 받는다. 받은 것이 없으면 테스트는 앱 아이콘을 쓴다.
"""
import json
import urllib.request
from pathlib import Path

OUT = Path(__file__).resolve().parent / "item-art"
HOME = "https://sg-hk4e-api.hoyolab.com/event/sol/home?lang=ko-kr&act_id=e202102251931481"
UA = {"User-Agent": "SumiLab-Checkin-Store/1.0"}


def main():
    OUT.mkdir(exist_ok=True)
    awards = json.loads(urllib.request.urlopen(urllib.request.Request(HOME, headers=UA), timeout=60).read())["data"]["awards"]
    rows = {}
    for a in awards:
        name = a["name"].replace("\xa0", " ").strip()
        url = a["icon"]
        if name in rows or not url.startswith("https://"):
            continue
        file = f"{len(rows)}.png"
        (OUT / file).write_bytes(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60).read())
        rows[name] = (url, file)
    (OUT / "items.tsv").write_text("".join(f"{n}\t{u}\t{f}\n" for n, (u, f) in rows.items()), encoding="utf-8")
    print(f"아이템 그림 {len(rows)}개: {', '.join(rows)}")


if __name__ == "__main__":
    main()
