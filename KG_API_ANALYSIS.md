# 元力KG音源 API 分析

> 来源: https://13413.kstore.vip/yuanli/kg.js
> 作者: 元力菌 (微信公众号: 元力菌)
> 版本: 1.2.0
> 解密日期: 2026-08-02

---

## 模块元数据

| 字段 | 值 |
|------|-----|
| platform | 元力KG |
| version | 1.2.0 |
| srcUrl | https://13413.kstore.vip/yuanli/kg.js |
| cacheControl | no-cache |
| primaryKey | id, album_id, album_audio_id |
| supportedSearchType | music, album, sheet |
| pagesize | 20 (0x14) |

---

## 通用请求头

```
User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/106.0.0.0 Safari/537.36
Accept: */*
Accept-Encoding: gzip, deflate
Accept-Language: zh-CN,zh;q=0.9
```

---

## 音质等级映射

| LX Music 音质 | API 参数值 |
|---------------|-----------|
| low | standard |
| standard | exhigh |
| high | lossless |
| super | hires |

---

## 接口详情

### 1. 歌曲搜索 (searchMusic)

```
GET https://songsearch.kugou.com/song_search_v2
```

| 参数 | 值 | 说明 |
|------|-----|------|
| keyword | 搜索词 | 用户输入 |
| page | 页码 | 从 1 开始 |
| pagesize | 20 | 固定 |
| userid | 0 | |
| clientver | '' | 空 |
| platform | WebFilter | |
| filter | 2 | |
| iscorrection | 1 | |
| privilege_filter | 0 | |
| area_code | 1 | |

**响应解析**: `response.data.lists → formatMusicItem`

**返回字段 (formatMusicItem 映射)**:
| LX 字段 | 酷狗原始字段 | 后备字段 |
|---------|-------------|---------|
| id | data.FileHash | data.Grp[0].FileHash |
| title | data.SongName | data.OriSongName |
| artist | data.SingerName | data.Singers[0].name |
| album | data.AlbumName | data.Grp[0].AlbumName |
| album_id | data.AlbumID | data.Grp[0].AlbumID |
| album_audio_id | 0 | 固定为 0 |
| duration | data.Duration | |
| artwork | data.Image.replace("{size}", "1080") | data.Grp[0].Image |
| 320hash | data.HQFileHash | undefined |
| sqhash | data.SQFileHash | undefined |
| ResFileHash | data.ResFileHash | undefined |

---

### 2. 专辑搜索 (searchAlbum)

```
GET http://msearch.kugou.com/api/v3/search/album
```

| 参数 | 值 |
|------|-----|
| version | 0x2394 |
| iscorrection | 1 |
| highlight | em |
| plat | 0 |
| keyword | 搜索词 |
| pagesize | 20 |
| page | 页码 |
| sver | 2 |
| with_res_tag | 0 |

**响应解析**: `response.data.info → map`
**返回**: id(albumid), artwork(imgurl), artist(singername), title(albumname), description(intro), date(publishtime)

---

### 3. 歌单搜索 (searchMusicSheet)

```
GET http://mobilecdn.kugou.com/api/v3/search/special
```

| 参数 | 值 |
|------|-----|
| format | json |
| keyword | 搜索词 |
| page | 页码 |
| pagesize | 20 |
| showtype | 1 |

**返回**: title(specialname), createAt(publishtime), description(intro), artist(nickname), coverImg(imgurl), gid, playCount, id(specialid), worksNum(songcount)

---

### 4. 获取播放链接 (getMediaSource) ⭐核心接口

```
GET https://music.haitangw.cc/kgqq1/kg.php?id=<歌曲Hash>&type=json&level=<音质参数>
```

| 参数 | 值 |
|------|-----|
| id | 歌曲 hash (对应 searchMusic 返回的 id) |
| type | json |
| level | standard/exhigh/lossless/hires (由音质等级映射) |

**响应**: `response.data.url` — 直接播放链接

**注意**: 返回的 url 可能是 HTTP 明文链接，微信环境需升级为 HTTPS 或强制 HTTPS 请求

---

### 5. 获取歌词 search (getLyric)

```
GET http://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=<title>&hash=<id>&timelength=<duration>
```

**特殊请求头**:
```
KG-RC: 1
KG-THash: expand_search_manager.cpp:852736169:451
User-Agent: KuGou2012-9020-ExpandSearchManager
XSRF-TOKEN: (cookie 方式)
withCredentials: true
```

**响应解析**: `response.candidates[0] → id, accesskey`

---

### 6. 下载歌词内容 (getLyricDownload)

```
GET http://lyrics.kugou.com/download?ver=1&client=pc&id=<id>&accesskey=<accessKey>&fmt=lrc&charset=utf8
```

**同上特殊请求头**
**响应**: `response.content` (Base64 编码的歌词内容，用 he.decode + CryptoJs Base64/Utf8 解码)

---

### 7. 榜单列表 (getTopLists)

```
GET http://mobilecdnbj.kugou.com/api/v3/rank/list?version=9108&plat=0&showtype=2&parentid=0&apiver=6&area_code=1&withsong=0&with_res_tag=0
```

**分类**: 热门榜单(classify=1/2), 特色音乐榜(classify=3), 全球榜(classify=4), 其他

---

### 8. 榜单详情 (getTopListDetail)

```
GET http://mobilecdnbj.kugou.com/api/v3/rank/song?version=9108&ranktype=0&plat=0&pagesize=100&area_code=1&page=1&volid=35050&rankid=<id>&with_res_tag=0
```

**返回**: 榜单歌曲列表，通过 formatMusicItem2 映射

---

### 9. 专辑详情 (getAlbumInfo)

```
GET http://mobilecdn.kugou.com/api/v3/album/song
```

| 参数 | 值 |
|------|-----|
| version | 0x2394 |
| albumid | 专辑 ID |
| plat | 0 |
| pagesize | 100 |
| area_code | 1 |
| page | 页码 |
| with_res_tag | 0 |

**返回**: 专辑内歌曲列表，字段包括 id(hash), title, artist, album, album_id, album_audio_id, artwork, 320hash(HQFileHash), sqhash(SQFileHash), origin_hash

---

### 10. 导入歌单 (importMusicSheet)

```
POST http://t.kugou.com/command/
```

解析酷狗短链 → 获取歌单信息 → 获取歌曲列表

| 参数 | 值 |
|------|-----|
| appid | 1001 |
| clientver | 9012 |
| mid | 21511157a05844bd085308bc76ef3343 |
| clienttime | 0x262efa1f |
| key | 36164c4015e704673c588ee202b9ecb8 |
| data | 短链 ID |

**第二步**:
```
POST http://www2.kugou.kugou.com/apps/kucodeAndShare/app/
```
获取歌单内的歌曲详情

**第三步** (精品化):
```
GET https://gateway.kugou.com/v2/get_res_privilege/lite?appid=1001&clienttime=1668883879&clientver=10112&dfid=2O3jKa20Gdks0LWojP3ly7ck&mid=70a02aad1ce4648e7dca77f2afa7b182&userid=390523108&uuid=92691C6246F86F28B149BAA1FD370DF1
```

---

## 与乐少 App 现有实现对比

| 功能 | 元力KG音源 | 乐少 App 当前实现 | 状态 |
|------|-----------|-----------------|------|
| 歌曲搜索 | songsearch.kugou.com/song_search_v2 | 使用酷狗API | 对应 |
| 获取播放链接 | music.haitangw.cc/kgqq1/kg.php | fetchKugouFallback 相同接口 | **一致** |
| 音质映射 | low→standard, standard→exhigh, high→lossless, super→hires | 类似映射 | 一致 |
| 歌词搜索 | lyrics.kugou.com/search | 独立实现 | 相似 |
| 歌词下载 | lyrics.kugou.com/download | 独立实现 | 相似 |

---

## 完整解密源码

见同目录文件: `kg_decrypted.js`

## 解密方法

1. 使用 obfuscator.io 的 base64 + RC4 双重解密
2. 字符串数组通过 `_0x3692()` 获取
3. 双参数调用用 RC4(自定义key) 解密
4. 单参数调用用纯 base64 解密（_0x3630 函数）
5. 所有字符串引用已完全还原为明文
