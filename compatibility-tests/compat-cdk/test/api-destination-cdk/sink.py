"""Capture synthetic API destination deliveries on an ephemeral HTTP port."""

import json
import pathlib
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import TCPServer

directory = pathlib.Path(sys.argv[1])


class Sink(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        delivery = {
            "path": self.path,
            "api_key": self.headers.get("X-Key"),
            "target_header": self.headers.get("X-Target"),
            "body": json.loads(body),
        }
        with (directory / "deliveries.jsonl").open("a") as output:
            output.write(json.dumps(delivery) + "\n")
        self.send_response(200)
        self.end_headers()

    def log_message(self, format, *args):
        pass


class Receiver(HTTPServer):
    def server_bind(self):
        # Startup uses the numeric address instead of waiting for reverse DNS.
        TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address


server = Receiver(("0.0.0.0", 0), Sink)
(directory / "port").write_text(str(server.server_port))
server.serve_forever()
