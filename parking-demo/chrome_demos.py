"""Serve the contest demos on loopback, including the native parking OCR for Chrome."""
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit, unquote
import argparse, json, subprocess, tempfile

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
BINARY=HERE/'주차메모.app/Contents/MacOS/ParkingDemo'
ROOTS={'gil': (HERE.parent/'simulator','index.html'), 'road': (ROOT/'roadstory/app/src/main/assets','index.html'), 'parking-original':(ROOT/'parking_demo','주차위치_데모.html')}
class Demo(SimpleHTTPRequestHandler):
    def translate_path(self, raw):
        parts=unquote(urlsplit(raw).path).strip('/').split('/')
        if parts[0]=='parking' and len(parts)==1: return str(HERE/'chrome.html')
        if parts[0]=='parking' and len(parts)==3 and parts[1]=='samples' and parts[2] in {f'{i:02}.jpg' for i in range(1,6)}: return str(HERE/'Samples'/parts[2])
        if parts[0] in ROOTS:
            base,entry=ROOTS[parts[0]]
            target=(base.joinpath(*parts[1:]) if len(parts)>1 else base/entry).resolve()
            if target.is_relative_to(base) and target.is_file() and target.suffix.lower() in {'.html','.js','.css','.json','.mp3','.m4a','.ogg','.wav','.svg','.png','.jpg','.webp','.ico'}: return str(target)
        return str(HERE/'_not_served_')
    def do_POST(self):
        host=f'127.0.0.1:{self.server.server_port}'
        if self.path!='/parking/read' or self.headers.get('Host')!=host or self.headers.get('Origin')!='http://'+host:
            return self.send_error(403)
        try: length=int(self.headers.get('Content-Length','0'))
        except ValueError: return self.send_error(400)
        if not 0<length<=15*1024*1024: return self.send_error(413)
        try:
            with tempfile.NamedTemporaryFile(suffix='.img') as photo:
                photo.write(self.rfile.read(length)); photo.flush()
                read=subprocess.run([str(BINARY),'--read',photo.name],capture_output=True,text=True,timeout=55,check=True)
            place,marker,status,_=read.stdout.rstrip('\n').split(' | ',3)
            data=json.dumps({'place':place,'marker':marker,'auto':status=='AUTO'},ensure_ascii=False).encode()
        except (OSError,ValueError,subprocess.SubprocessError):return self.send_error(422)
        self.send_response(200);self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Content-Length',str(len(data)));self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(data)
    def log_message(self,*args):pass
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=8794);args=parser.parse_args()
    server=ThreadingHTTPServer(('127.0.0.1',args.port),Demo)
    print('데모 주소 http://127.0.0.1:'+str(server.server_port),flush=True);server.serve_forever()
