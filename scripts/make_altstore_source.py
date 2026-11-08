#!/usr/bin/env python3
"""Erzeugt die AltStore-/SideStore-Quelle (altstore-source.json) für ein Release.

Format geprüft am Quellcode von AltStore (AltStoreCore/Model/Source.swift, StoreApp.swift, AppVersion.swift):
Pflicht sind name/identifier (Quelle), name/bundleIdentifier/developerName/localizedDescription/iconURL (App)
und version/date/downloadURL/size (Version). Datum im ISO-8601-Format.
"""
import argparse
import json
import os
from datetime import datetime, timezone

parser = argparse.ArgumentParser()
parser.add_argument("--ipa", required=True)
parser.add_argument("--version", required=True)
parser.add_argument("--build", required=True)
parser.add_argument("--repo", required=True)
parser.add_argument("--sha", required=True)
parser.add_argument("--notes", default="")
parser.add_argument("--out", required=True)
args = parser.parse_args()

# Signaturzeilen aus dem Commit-Text entfernen
notes = "\n".join(l for l in args.notes.splitlines()
                  if not l.startswith(("Co-Authored-By:", "Claude-Session:"))).strip()

tag = f"v{args.version}"
base = f"https://github.com/{args.repo}"
source = {
    "name": "MP3 Player",
    "identifier": "com.backewatsch.mp3player.source",
    "subtitle": "Updates für den MP3 Player",
    "sourceURL": f"{base}/releases/latest/download/altstore-source.json",
    "website": base,
    "apps": [
        {
            "name": "MP3 Player",
            "bundleIdentifier": "com.backewatsch.mp3player",
            "developerName": args.repo.split("/")[0],
            "subtitle": "MP3s abspielen und herunterladen",
            "localizedDescription": "MP3-Player mit Playlists, Hintergrundwiedergabe und internem Browser, "
                                    "der Audiodateien herunterlädt und direkt in die Playlist „Heruntergeladen“ legt.",
            "iconURL": f"https://raw.githubusercontent.com/{args.repo}/{args.sha}/MP3Player/Assets.xcassets/AppIcon.appiconset/AppIcon.png",
            "tintColor": "FA2E66",
            "category": "entertainment",
            "screenshotURLs": [],
            "versions": [
                {
                    "version": args.version,
                    "buildVersion": args.build,
                    "date": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                    "localizedDescription": notes or f"Build {args.build}",
                    "downloadURL": f"{base}/releases/download/{tag}/MP3Player.ipa",
                    "size": os.path.getsize(args.ipa),
                    "minOSVersion": "16.0",
                }
            ],
            # "appPermissions" ist optional und wird weggelassen (die App braucht keine besonderen Rechte)
        }
    ],
    "news": [],
}

with open(args.out, "w", encoding="utf-8") as f:
    json.dump(source, f, ensure_ascii=False, indent=2)
print(json.dumps(source, ensure_ascii=False, indent=2))
