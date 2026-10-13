"""Download an explicitly supplied HTTPS source; never log its URL."""
import argparse
import ipaddress
from pathlib import Path
import socket
from urllib.parse import urlparse
from urllib.request import HTTPSHandler, HTTPRedirectHandler, Request, build_opener

MAX_BYTES = 64 * 1024 * 1024


def validate_url(url):
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("Une URL HTTPS publique sans identifiants est requise.")
    for record in socket.getaddrinfo(parsed.hostname, parsed.port or 443, type=socket.SOCK_STREAM):
        if not ipaddress.ip_address(record[4][0]).is_global:
            raise ValueError("Une adresse publique est requise.")


class SafeRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, url):
        validate_url(url)
        return super().redirect_request(request, response, code, message, headers, url)


def download(url, destination):
    validate_url(url)
    opener = build_opener(HTTPSHandler(), SafeRedirect())
    request = Request(url, headers={"User-Agent": "Modeliseur3D/trellis"})
    with opener.open(request, timeout=60) as response, destination.open("wb") as output:
        total = 0
        while chunk := response.read(1024 * 1024):
            total += len(chunk)
            if total > MAX_BYTES:
                raise ValueError("Source supérieure à 64 Mio.")
            output.write(chunk)
    if not total:
        raise ValueError("Source vide.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("url")
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    try:
        download(args.url, args.destination)
    except Exception:
        args.destination.unlink(missing_ok=True)
        raise SystemExit("Téléchargement interrompu : vérifiez la source HTTPS publique et sa taille.")
