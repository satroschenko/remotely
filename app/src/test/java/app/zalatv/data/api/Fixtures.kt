package app.zalatv.data.api

/** Hand-made, sanitized samples shaped like real portal responses (2026-10-15). */
object Fixtures {
    const val LOGIN1_OK = """{"serport":"8080","serhttpsport":"443","SerIpAddress":"178.124.183.238","ReturnCode":"0","SerTerminalNo":"177","serdomain":"itvepg177.tv.zala.by","EncryToken":"ENC123","iemg":"0","AuthStr":"","EmgInfo":""}"""
    const val LOGIN1_NO_USER = """{"ReturnCode":"70116101","ErrorMsg":"User not exist"}"""
    const val LOGIN2_OK = """{"ReturnCode":"0","ErrorMsg":"","authenticator":"AUTHHEX"}"""
    const val LOGIN3_OK = """{"ReturnCode":"0","UserToken":"TOKEN1","UserTokenExpiredTime":"2026.10.16 22:28:30","Password":"x","epghttpsdomainbackup":""}"""
    const val LOGIN3_BAD_PWD = """{"ReturnCode":"70116206","ErrorMsg":"User name or password is incorrect"}"""
    const val LOGIN4_OK = """{"ReturnCode":"0","StypeUrl":"frame1255"}"""

    const val CHANNELS = """{"totalcount":3,"errormsg":"","returncode":"0","data":[
 {"endtime":"2026.10.15 22:10:00","channelname":"Беларусь 1","begintime":"2026.10.15 21:50:00","usermixno":1,"tsavailable":"90","mediaservices":"3","channelcode":"ch09031805502782081720","mixno":"2","prevuename":"Новости","definition":1,"posterimage":"../images/markurl/mark_1.jpg","tvodavailable":"1","columncode":"0803","utcbegintime":"2026.10.15 18:50:00","utcendtime":"2026.10.15 19:10:00"},
 {"channelname":"ОНТ","usermixno":2,"channelcode":"ch09031805520275474789","mixno":"3","prevuename":"Чудо (12+)","definition":1,"posterimage":"../images/markurl/mark_2.jpg","tvodavailable":"1","columncode":"0803","utcbegintime":"2026.10.15 18:35:00","utcendtime":"2026.10.15 19:50:00"},
 {"channelname":"Без номера","channelcode":"chX","mixno":"9"}
]}"""
    const val SUBSCRIBED = """{"returncode":"0","errormsg":"","data":"149,1,2,7"}"""
    const val EPG = """{"totalcount":2,"utctime":"20261015190615","errormsg":"","returncode":"0","data":[
 {"endtime":"2026.10.14 16:40:00","genre":"Ток-шоу","begintime":"2026.10.14 16:20:00","description":"Описание 2","channelcode":"ch09031805520275474789","prevuename":"Второе","prevuecode":"00000000070072979524","utcbegintime":"2026.10.14 13:20:00","utcendtime":"2026.10.14 13:40:00","istimeshift":"1"},
 {"endtime":"2026.10.14 16:20:00","genre":"Новости","begintime":"2026.10.14 16:00:00","description":"Описание 1","channelcode":"ch09031805520275474789","prevuename":"Наши новости (12+)","prevuecode":"00000000070072979523","utcbegintime":"2026.10.14 13:00:00","utcendtime":"2026.10.14 13:20:00","istimeshift":"0"}
]}"""
    const val TVOD_DEPTH = """{"returncode":"0","errormsg":"Success","data":{"tvodchannellist":[{"mixno":"332","tvodtime":"172800"},{"mixno":"34","tvodtime":"86400"}]}}"""
    const val PLAYURL = """{"errormsg":"Success","returncode":"0","data":[{"playurl":"https://itv1.tv.zala.by:6430/x/manifest.mpd?AuthInfo=A&JITPDRMType=NO","columncode":"0803","httpsplayurl":"https://itv1.tv.zala.by:6430/x/manifest.mpd?AuthInfo=B&JITPDRMType=NO","channelcode":"ch09031805520275474789","mixno":"3"}]}"""
    const val PLAYURL_NULL_PARAM = """{"returncode":"10070501","errormsg":"param is null."}"""
    const val TVOD_AUTH_OK = """{returnCode:"0",payLock:"0",definition:"1",errorMsg:"Успешно",authid:"1344173021",productObj:[]}"""
    const val TVOD_AUTH_LOCKED = """{returnCode:"98",payLock:"0",errorMsg:"x",authid:"",productObj:[]}"""
    const val TVOD_PAGE = """
<script>
    var advertisecontent = "3";
    parent.extrWin.document.location = "/iptvepg/frame1255/control_transit_tvodplay.jsp?authid=1";
    var tvodPlayUrl = "https://itv2.tv.zala.by:6430/x/manifest.mpd?AuthInfo=C&BreakPoint=0&programbegin=20261014142000+00&programend=20261014150000+00";
    parent.jsPlayTVOD(tvodPlayUrl);
</script>"""
    const val REBUILD_SESSION_HTML = """<html><head><title>rebuildsession</title></head></html>"""
}
