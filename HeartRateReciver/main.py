import os
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import logging
import socket

SERVER_PORT = 9025

LOG_CSV = False
START_VISUALIZATION = False

CURRENT_USER = os.getenv("USER", "default_user")
METRIC_TARGET = os.getenv("METRIC_TARGET", CURRENT_USER)

# OSC controller

try:
    from osc_controller import HeartRateOSCSender, OSC_IP, OCS_PORT

    heartRateOSCSender = HeartRateOSCSender(OSC_IP, OCS_PORT)

    def osc_send_heart_rate(heart_rate, timestamp=None):
        heartRateOSCSender.send_heart_rate(heart_rate, timestamp)

except ImportError:
    print("Warning: OSC controller not available. Heart rate will not be sent to OSC.")

    def osc_send_heart_rate(heart_rate, timestamp=None):
        _, _ = heart_rate, timestamp

# CSV logger

if LOG_CSV:

    import datetime
    from csv_logger import CSVLogger

    csv_logger = CSVLogger.create_logger_from_date_time(datetime.datetime.now())

    def csv_log_heart_rate(heart_rate, timestamp):
        csv_logger.write(timestamp, heart_rate)

else:
    def csv_log_heart_rate(heart_rate, timestamp):
        _, _ = heart_rate, timestamp

# Core

class HeatRateManager:

    def __init__(self):
        self.heart_rate = 0.0
        self.timestamp = 0

    def set_heart_rate(self, heart_rate, timestamp):
        self.heart_rate = heart_rate
        self.timestamp = timestamp

        # Send heart rate to OSC if available
        osc_send_heart_rate(heart_rate, timestamp)

        # Log heart rate to CSV if available
        csv_log_heart_rate(heart_rate, timestamp)

    def get_heart_rate(self):
        return self.heart_rate

    def get_timestamp(self):
        return self.timestamp


heatRateManager = HeatRateManager()


class HTTPRequestHandler(BaseHTTPRequestHandler):

    def do_GET(self):

        if self.path == '/heart-rate-endpoint' or self.path == '/hr':

            # return heart rate

            self.send_response(200)
            self.send_header('Content-type', 'text/plain')
            self.send_header('Refresh', '5')
            self.end_headers()

            self.wfile.write(
                f"{heatRateManager.get_heart_rate():.0f}".encode('utf-8'))

            return
        
        # prometheus metrics endpoint
        if self.path == '/metrics':

            self.send_response(200)
            self.send_header('Content-type', 'text/plain; version=0.0.4')
            self.end_headers()

            lines = [
                f"# HELP heart_rate_bpm Current heart rate",
                f"# TYPE heart_rate_bpm gauge",
                f"heart_rate_bpm{{target=\"{METRIC_TARGET}\"}} {heatRateManager.get_heart_rate():.0f}"
            ]
            metrics = "\n".join(lines) + "\n"

            self.wfile.write(metrics.encode('utf-8'))

            return

        # invalid path

        self.send_response(404)
        self.end_headers()

    # @override
    def do_POST(self):

        if self.path == '/heart-rate-endpoint' or self.path == '/hr':

            # update heart rate

            content_length = int(self.headers['Content-Length'])
            post_data = self.rfile.read(content_length)

            payload = post_data.decode('utf-8')

            payload_json = json.loads(payload)

            self.send_response(200)
            self.end_headers()

            logging.info(payload)

            # set heart rate

            heart_rate_keys = ["heartRate", "heart_rate", "heartrate", "hr", "bpm"]
            timestamp_keys = ["timestamp", "time", "ts"]

            heart_rate = None
            timestamp = None

            for key in heart_rate_keys:
                if key in payload_json:
                    heart_rate = payload_json[key]
                    break
            else:
                logging.error("No heart rate key found in payload")
                return
            
            for key in timestamp_keys:
                if key in payload_json:
                    timestamp = payload_json[key]
                    break
            else:
                logging.error("No timestamp key found in payload")
                return

            heatRateManager.set_heart_rate(heart_rate, timestamp)

            return

        # invalid path

        self.send_response(404)
        self.end_headers()

def main():
    logging.basicConfig(level=logging.INFO)

    logging.info('Starting HTTP server...')

    ip_addresses = [i[4][0]
                    for i in socket.getaddrinfo(socket.gethostname(), None)]
    logging.info('IP addresses: %s', ip_addresses)
    logging.info('Server port: %d', SERVER_PORT)

    server_address = ('', SERVER_PORT)
    http_server = HTTPServer(server_address, HTTPRequestHandler)

    try:
        http_server.serve_forever()
    except KeyboardInterrupt:
        pass

    logging.info('Stopping HTTP server...')
    http_server.server_close()

    # reset OSC heart rate
    osc_send_heart_rate(0.0)

if __name__ == '__main__':
    main()
