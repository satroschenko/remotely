#!/usr/bin/env python3
"""Live check of the Zala portal API used by the app. Reads ../.env (login=, pass=). Never prints secrets.

Usage: python3 -I tools/smoke.py
"""
import json, os, re, sys, time, urllib.parse, urllib.request, http.cookiejar, datetime

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
env = dict(l.strip().split('=', 1) for l in open(os.path.join(ROOT, '.env')) if '=' in l)
LOGIN, PWD = env['login'], env['pass']
cj = http.cookiejar.CookieJar()
op = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(cj))
ts = lambda: str(int(time.time() * 1000))
T = '&TerminalFlag=16&TerminalOsType=2&iscors=1'

def get(url, data=None):
    r = op.open(url, data=urllib.parse.urlencode(data).encode() if data else None, timeout=20)
    return r.geturl(), r.read().decode('utf-8', 'replace').strip()

def js(t): return json.loads(t[1])

def step(name, fn):
    try:
        v = fn(); print(f"✓ {name}"); return v
    except Exception as e:
        print(f"✗ {name}: {type(e).__name__}: {str(e)[:200]}"); sys.exit(1)

q = urllib.parse.quote
d = step('1 mobilelogin', lambda: js(get(f"https://tv.zala.by:443/iptvepg/datasource/mobilelogin.jsp?Action=Login&UserID={q(LOGIN)}{T}&timestamp={ts()}")))
assert d['ReturnCode'] == '0', d.get('ReturnCode')
epg = f"https://{d['serdomain']}:{d['serhttpsport']}"
enc = d.get('EncryToken') or ts()
a = step('2 getauthenticator', lambda: js(get(f"{epg}/iptvepg/datasource/getauthenticator.jsp?encrytoken={enc}&userid={q(LOGIN)}&iemg={d.get('iemg','')}&authStr={q(d.get('AuthStr',''))}&emgInfo={q(d.get('EmgInfo',''))}&userpwd={q(PWD)}&EpgServer={epg}&stbid=zxyzxy&mac=000000000000{T}&timestamp={ts()}")))
t = step('3 mobilegetusertoken', lambda: js(get(f"{epg}/iptvepg/datasource/mobilegetusertoken.jsp?EncryToken={enc}&UserID={q(LOGIN)}&EpgServer={epg}&clientId=zxyzxy&Authenticator={a['authenticator']}{T}&timestamp={ts()}")))
assert t['ReturnCode'] == '0', f"login rc {t['ReturnCode']}"
print('  token expires', t.get('UserTokenExpiredTime'))
p = step('4 mobileportalauth', lambda: js(get(f"{epg}/iptvepg/datasource/mobileportalauth.jsp?UserID={q(LOGIN)}&UserToken={t['UserToken']}&STBID=zxyzxy{T}&timestamp={ts()}")))
fr = p['StypeUrl'] if 'frame' in p['StypeUrl'] else 'frame' + p['StypeUrl']
F = f"{epg}/iptvepg/{fr}"
get(f"{F}/frameset_builder.html?islogin=1&userToken={t['UserToken']}&lan=ru")
ch = step('channels', lambda: js(get(f"{F}/action/sdk_getchannellist.jsp?columncode=0803")))
sub = step('subscription', lambda: js(get(f"{F}/action/sdk_getsubscribechannel.jsp")))
print(f"  {ch['totalcount']} channels, {len(sub['data'].split(','))} subscribed")
c = next(x for x in ch['data'] if x.get('tvodavailable') == '1')
print(f"  test channel: {c['usermixno']}. {c['channelname']}")
live = step('live url', lambda: js(get(f"{F}/action/sdk_getbatchtvplayurl.jsp", {'channelcodes': c['channelcode'], 'columncodes': c['columncode'], 'channelnostr': c['mixno'], 'definition': str(c['definition']), 'jitpdrmType': '', 'isSpecial': '0', 'profile': ''}))['data'][0]['playurl'])
m = step('live manifest', lambda: get(live)[1])
assert 'ContentProtection' not in m, 'DRM appeared in live manifest!'
print('  live: no DRM,', re.search(r'type="(\w+)"', m).group(1))
now = datetime.datetime.now(datetime.timezone.utc)
f = lambda x: q(x.strftime('%Y.%m.%d %H:%M:%S'))
e = step('epg (yesterday)', lambda: js(get(f"{epg}/iptvepg/frame1258/sdk_getprevuellist.jsp?channelcode={c['channelcode']}&utcbegintime={f(now - datetime.timedelta(hours=26))}&utcendtime={f(now - datetime.timedelta(hours=20))}")))
pr = e['data'][len(e['data']) // 2]
print(f"  programme: {pr['begintime']} {pr['prevuename']}")
auth = step('archive auth', lambda: get(f"{F}/action/get_authTvodresult.jsp?columnid={c['columncode']}&channelid={c['channelcode']}&contentcode={pr['prevuecode']}&mixno={c['usermixno']}&leefocus=0")[1])
aid = re.search(r'authid:"([^"]*)"', auth).group(1)
page = step('archive page', lambda: get(f"{F}/channel_tvod_play.jsp?authid={aid}&contentcode={pr['prevuecode']}&channelcode={c['channelcode']}&columncode={c['columncode']}&definition={c['definition']}&leefocus=0&jitpdrmType=NO")[1])
url = re.search(r'var tvodPlayUrl = "([^"]+)"', page).group(1)
am = step('archive manifest', lambda: get(url)[1])
assert 'ContentProtection' not in am, 'DRM appeared in archive manifest!'
print('  archive: no DRM,', re.search(r'mediaPresentationDuration="([^"]+)"', am).group(1))
print('ALL OK')
