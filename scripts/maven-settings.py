#!/usr/bin/env python3
"""Make Maven honor the runtime HTTPS proxy without exposing credentials."""
import os,urllib.parse,html
from pathlib import Path
root=Path(__file__).resolve().parents[1];p=root/'.local/maven-settings.xml';p.parent.mkdir(exist_ok=True)
u=urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY',os.environ.get('https_proxy','')))
s='<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"><localRepository>'+html.escape(str(root/'.local/m2/repository'))+'</localRepository>'
if u.hostname:
 s+='<proxies><proxy><id>environment</id><active>true</active><protocol>http</protocol><host>'+html.escape(u.hostname)+'</host><port>'+str(u.port or 80)+'</port>'
 for key,value in [('username',u.username),('password',u.password)]:
  if value:s+='<'+key+'>'+html.escape(urllib.parse.unquote(value))+'</'+key+'>'
 s+='</proxy></proxies>'
s+='</settings>';p.write_text(s);p.chmod(0o600)
