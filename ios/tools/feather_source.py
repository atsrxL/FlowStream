#!/usr/bin/env python3
"""Writes an AltStore / Feather source (JSON) listing every ios-v* GitHub release of FlowStream.

Usage: GH_TOKEN=... python3 ios/tools/feather_source.py --repo owner/name [--build N] > source.json
Each release must carry an asset named FlowStream-<version>-ios-unsigned.ipa.
"""
import argparse, json, re, subprocess

BUNDLE_ID = "dev.flowstream.player.ios"

def releases(repo):
    out = subprocess.run(["gh", "api", "--paginate", f"repos/{repo}/releases?per_page=100"],
                         check=True, capture_output=True, text=True).stdout
    # --paginate concatenates JSON arrays: "][" between pages.
    return json.loads("[" + out.strip()[1:-1].replace("][", ",") + "]") if out.strip() not in ("", "[]") else []

def vkey(v):
    return [int(x) if x.isdigit() else x for x in re.split(r"[.\-]", v)]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--build", help="buildVersion of the newest version")
    a = ap.parse_args()
    raw = f"https://raw.githubusercontent.com/{a.repo}/ios/ipados/ios/FlowStream/Resources/Icons/AppIcon60x60@3x.png"
    versions = []
    for r in releases(a.repo):
        m = re.fullmatch(r"ios-v(\d+(?:\.\d+)*)", r.get("tag_name", ""))
        if not m or r.get("draft"):
            continue
        v = m.group(1)
        asset = next((x for x in r.get("assets", []) if x["name"] == f"FlowStream-{v}-ios-unsigned.ipa"), None)
        if not asset:
            continue
        body = (r.get("body") or "").strip()
        versions.append({
            "version": v,
            "date": (asset.get("updated_at") or r.get("published_at") or "")[:19] + "Z",
            "localizedDescription": body[:1500] or f"映流 {v}",
            "downloadURL": asset["browser_download_url"],
            "size": asset["size"],
            "minOSVersion": "16.0",
        })
    versions.sort(key=lambda x: vkey(x["version"]), reverse=True)
    if not versions:
        raise SystemExit("no ios-v* release with an IPA asset")
    if a.build:
        versions[0]["buildVersion"] = a.build
    top = versions[0]
    app = {
        "name": "映流",
        "bundleIdentifier": BUNDLE_ID,
        "developerName": "atsrxL",
        "subtitle": "SMB / WebDAV 网络视频播放器",
        "localizedDescription": "映流 FlowStream：播放 SMB / WebDAV 上的视频。九区手势、长按调速、横拖预览、上下滑切换、标记、预读、画中画。iPhone / iPad 通用，仅支持 MP4 / MOV / M4V / 3GP。",
        "iconURL": raw,
        "tintColor": "0A84FF",
        "category": "entertainment",
        "version": top["version"],
        "versionDate": top["date"],
        "versionDescription": top["localizedDescription"],
        "downloadURL": top["downloadURL"],
        "size": top["size"],
        "versions": versions,
    }
    source = {
        "name": "映流 FlowStream",
        "identifier": "dev.flowstream.source",
        "subtitle": "映流 iOS / iPadOS 未签名构建",
        "website": f"https://github.com/{a.repo}",
        "iconURL": raw,
        "tintColor": "0A84FF",
        "apps": [app],
        "news": [],
    }
    print(json.dumps(source, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
