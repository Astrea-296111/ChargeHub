#!/usr/bin/env python3
"""BuildKit-only Maven proxy settings; never COPY this private file into an image."""
import html
import os
from pathlib import Path
from urllib.parse import unquote, urlsplit
root=Path(__file__).resolve().parents[2]
url=urlsplit(os.environ.get('HTTPS_PROXY',os.environ.get('https_proxy','')))
text='<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">'
if url.hostname:
    text+='<proxies><proxy><id>runtime</id><active>true</active><protocol>http</protocol><host>'+html.escape(url.hostname)+'</host><port>'+str(url.port or 80)+'</port>'
    for name,value in [('username',url.username),('password',url.password)]:
        if value: text+='<'+name+'>'+html.escape(unquote(value))+'</'+name+'>'
    text+='</proxy></proxies>'
text+='</settings>'
path=root/'.local/container-maven-settings.xml';path.parent.mkdir(exist_ok=True)
path.write_text(text);path.chmod(0o600)
print('Private BuildKit Maven settings generated; no host repository path or credential output.')

# The platform-provided CA is trusted by the host but not necessarily the builder JDK.
# Only this public CA is imported into an ephemeral BuildKit tmpfs truststore.
ca_path=root/'.local/container-maven-ca.crt'
provided=os.environ.get('CODEX_PROXY_CERT','')
ca_path.write_bytes(Path(provided).read_bytes() if provided and Path(provided).is_file() else b'')
ca_path.chmod(0o600)
print('Optional platform CA mount prepared; TLS and artifact checksum checks remain enabled.')
