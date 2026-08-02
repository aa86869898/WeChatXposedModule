/**
 * @name 长青SVIP音源
 * @description 音源更新，关注微信公众号: 元力菌
 * @version 1.0.0
 * @author 元力菌
 * @mail 微信公众号: 元力菌
 * @homepage 微信公众号: 元力菌
 * @update_url https://
 */
'use strict';
const _0x5cfaaf = _0xf53d,
  _0x211e3b = _0x3630;
(function(_0x109a7b, _0x86641) {
  const _0x5f4c29 = _0x3630,
    _0x63f677 = _0xf53d,
    _0x4ed56e = _0x109a7b();
  while (!![]) {
    try {
      const _0x597ec1 = -parseInt("1549544NwMWKZ") / 0x1 + parseInt("716xCvstp") / 0x2 * (-parseInt("12735IMFWCq") / 0x3) + parseInt("2716AMRllP") / 0x4 * (parseInt("11265utSqni") / 0x5) + parseInt("4098PhnkaD") / 0x6 * (parseInt("1211sMbhYX") / 0x7) + -parseInt("7842088irmmyv") / 0x8 + -parseInt("15611553ogbEAy") / 0x9 + parseInt("50192450FkYfQf") / 0xa;
      if (_0x597ec1 === _0x86641) break;
      else _0x4ed56e['push'](_0x4ed56e['shift']());
    } catch (_0x435cd1) {
      _0x4ed56e['push'](_0x4ed56e['shift']());
    }
  }
}(_0x3692, 0xd7973));
const _0x2b4998 = {};
_0x2b4998["value"] = !![], Object["defineProperty"](exports, "__esModule", _0x2b4998);
const axios_1 = require("axios"),
  cheerio_1 = require("cheerio"),
  CryptoJs = require("crypto-js"),
  he = require('he'),
  pageSize = 0x14;

function _0x3692() {
  const _0x4ccddf = ['DMpdVttcPCo4WOG', 'BwfW', 'ywXIDw1FAwq', 'CMDQwgm', 'AKTVsui', 'bSoJdConarm', 'uL3dPWlcVW', 'WP/dG8kLWPW', 'z0TSCwm', 'W47cVSoMjmkz', 'Cg9ZDa', 'ASknW65jW70', 'BwLK', 'W47cLqK2WRz3', 'WPdcVCkNW4jtW57dT8k5wW', 'AMvPu2q', 'WRJcUgpcTJ0', 'jeeVW47dLCoN', 'W77dP8oxW5nf', 'omkrhH4H', 'hCkHW7KurCoBgG5R', 'W5dcQSkntmkW', 'CgXHEq', 'W7ddG00D', 'WPDKd8oy', 'q2XgquS', 'WRJcGZZdVbW', 'DLHRrLO', 'y0Ttt2q', 'WPlcSCkPWQ09', 'abRcNLKmWR3cTGNdRmkluSo4eCoxWPq', 'EL/dImoNrq', 'WRPTWPb+', 'DhFcQddcNCkKWOyY', 'WQ5JW6hcPCoH', 'yN3dSW', 'ugzhChi', 'C2LUz2vYBMfTzq', 'CMfUA2LK', 'W4JcUmoMfSkE', 'W5C4WPvbWRu', 'C3vWzxi', 'vSk1xb3dMG', '5ys05yIzdrW', 'WOdcNSk6W7tdMq', 'tM1UB1G', 'BMLJA25HBwu', 'zLHjA2i', 'vMX0rhe', 'xmkjEbRdIq', 'jNDPDgHFCMvZx3rHzZ0W', 'eX8FlSoJWOK0ESkXW7q', 'o8osWPWRFq', 'u3v0DuC', 'rfSmWR08AaG6s8okjJC', 'BvDfz3i', 'ictdV8kQDa', 'j8o+WOBdN8oWWQJdHG', 'WPpdLmo9sHjZ', 'q0q2jCkA', 'iGJcQSkhW7XYWOK', 'WP/cU8kmW6/dUa', 'xNCKo8ky', 'nCoJnt4SfG', 'oCkyoa', 'odRcUmkIW6a', 'dCkLhmo7WQ4', 'zgvMAw5LuhjVCgvYDhK', 'W7qoWQ1eWQe', 'W7mnWPT0WO8', 'EhnYzKnVB2TPzu5HBwu', 'xgpcSCkdhG', 'jmotadj2r8k5fNO4', 'W4VdUNFdSSou', 'WRBdNCkqWP03', 'FSkDbchdRq', 'W4hcGCo7nSk9', 'v8kBW4LHW4y', 'sLftsgu', 'zxHWyw5Kx3nLyxjJAf9Tyw5Hz2vYlMnWCdO4nti3mZyXnJK6nduX', 'WQFcOZddOZS', 'W6K0W5aTW5LnufWDWOetWPhdOW', 'WOT/WOfTW4q0d1abWPG', 'rgBdSsBcRCoJWQiAW4qV', 'y8k4W4nUW7VcTWJdTa', 'uL/cQG', 'nda5ofbOBMTHra', 'zxHOAwDO', 'r8k7rXJdGfFdICkS', 'sMTUuvO', 'x0xcVCoGFWmbW5DdumoVxCkhWOJcINOtdmkTW7/cIMpcJColWQ3cMmk4W6CCctRdJ8kHWRGlW6FcGq', 'ASolWR/dVc4', 'mmoJadvC', 'W6/dS3ddMCoqbubo', 'qSkYD2iOfSoer087W7hcLtmR', 'y3zbz3G', 'svv1rue', 'vxrMoa', 'kmklimoQ', 'yxbWAwq', 'Ef8PWOG4', 'sez6sLK', '54M56iMY6z+Z5lMq5QAC', 'vefvEfe', 'W5FdQ8o0W7LIreTMW7ychCk0W5RdSq', 'amookwpdLW', 'BwvKAweUC3rVCMuUA3vNB3uUy29T', 'CMvWBgfJzq', 'b8o3jdDZuCkza2a9W40', 'WQNdU8k7W4BcHa', 'WRJdHCo5Fqm', 'xLKhWRm9CG', 'D0PQA0G', 'CfnQC28', 'Aw1NDxjS', 'g8k9W6m', 'Derxs1O', 'z3PPCcWGzgvMBgf0zq', 'FflcR8k/', 'W6JdO8oOW7vN', 'FXdcIdZdHq', 'y29SBgvJDf90ExbL', 'uLrys24', 'h8oRitn2s8kwh30', 'x00FWR4VEqG', 'AfLpuMS', 'W7fZgXi', 'r3jW', 'WR5bW5FcHCoM', 'ndaW', 'WQxcIspdVby', 'wvOgWR8', 'mcNdSCki', 'WOZcLmkjW6xdOG', 'CNRdMCoXACkFWQqAWPO', 'W6ZcMmkPxCk5', 'DNxcUSkyWQr9W4VcN8kdDG', 'WODUW4xcHSo5WQKNwa', 'qwXIDw1jra', 'ChvIBgLZAhrPBwu', 'WONcQ1dcRIddLSk3', 'x00dWRm6FZi+umomlsO', 'w1tcP8o3Efe', 'WQNdUmkMWOyw', 'ChRdKSoHASk0WRWEWOv5', 'tKqgWRCGBHKWuSog', 'wLfNyu0', 'gCk2W7q', 'uMvZrMLSzuHHC2G', 'zMLSzw5HBwu', 'wMDWuKy', 'W6JcNJOkWP4', 'rM1zDMq', 'AvHqr2G', 'W47cLq8KWQe', 'kSkjWOiC', 'BSoqWRJdPYi', 'mq07omkw', 'Aw5KzxHpzG', 'Ahr0Chm6lY9NyxrLD2f5lMT1z291lMnVBs92mI9NzxrFCMvZx3bYAxzPBgvNzs9SAxrLp2fWCgLKpteWmdeMy2XPzw50DgLTzt0XnJy4odGZodC5jMnSAwvUDhzLCJ0XmdeXmIzKzMLKptjpm2PlytiWr2rRCZbmv29QudnSEtDJAYzTAwq9nZbHmdjHywqXy2u0nJq4ztDKy2e3n2yYywzHn2iXodiMDxnLCMLKptm5mduYmZeWocz1DwLKptKYnJKXqZyYndzgodzgmJHcmtq5qKfbmuzemZCWreyX', 'C3bLy2LHBg5HBwu', 's0fPq2C', 'lWlcOCk1W7jJWOu', 'W4NcTL3dSCki', 'WQpcQYpdQI01it4', 'uu15vuG', 'zgzPza', 'g8o3gSoD', 'cCkOWQqnWR8', 'DMvYC2LVBG', 'c8oVhSoz', 'W5iYWPC', 'EwjsBu4', 'rg9fwfi', 'o8kqWPmbWQu', 'vxzLEei', 'F27dRcK', 'gCoPudqP', 'hJ8OgSoJWPWCvmkAW44', 'W6JcQSki', 'zwHQB3y', 'ALLft0C', 'mteYnJv1DfnXBMK', 'Bg9ZC2XLC3m', 'WOfNW5m', 'W6FcSmo+fq', 'n8ktmZmmW4ZdJa', 'DvfJBu4', 'u2LUz2vYCW', 'nZe2Een2C3rW', 'uNDKufi', 'sffgAwXLsgfZAa', 'uKvoyLy', 'A8k5W4zUW6tcHa', 'WRFcSCkwE24', 'W6hcQSkmvq', 'o8kFW4BcTmkq', 'vLdcImkbWRSIWQZdKCkDA8k0WRRcNrH3W7v8mKlcH2BcJdKqW6JdVmoPWQhcRhBdVCk/WOu1W6VcLCoYW5y/W5VdOge', 'zgf0yq', 'A0COW5i', 'vgL6thy', 'wwm/iCkE', 'WOrQWRzgWRa', 'ANnVBG', 'WQ7cJSkVWPWf', 'ASoFWRJdTIu', 'gSkYW7qf', 'W5ldVmoRW5nJ', 'sfDxwxi', 'DxFdHmo1', 'W4JdT3BdJSol', 'gSoTzapdPWK', 'C3zLCG', 'WRhcS8kqsJGUW7pdTSoxD0ZdM1DHzxZcQdpdVtdcK8kYk8kEWO1jW4WmqSovW41OwvxdSvvdWPdcPwjzyXBcS2hcQSotWRtcPmkyn8kk', 'WPVcSuRcOshdHCkPyW', 'gCo4fwRdM2a', 'WORcGmkiW6JdIW', 'nCkJW7uxCG', 'BgvUz3rO', 'xbVcIXBdLmoZWO8nW7FcLq', 'WQyHWPzWWPWbduC', 'DCkGW5tdGSo8WOtdGbmsuG', 'xmk0tXW', 'nSkEpZ0p', 'vgfdEhC', 'rLzQCg8', 'FhdcOmotAW', 'ms4YlJa', 'AsVcJrJdHG', 'yxzWtNO', 'W6/dT23dNComeW', 'dCo4e2O', 'z2v0', 'CLPvCfC', 'uKxdPHdcIG', 'fSkfWQCCWOW', 'sqhcKcpdVq', 'mrJcMLK', 'C2HLzxq', 'uhRdKSoHASkLWRmsWO0', 'zwDYteW', 'fcK0pSky', 'DM5fzgq', 'mCk8W7KRrG', 'WOJcV8kVWQ0', 'EMHOs2K', 'mti3mZvjtuzxq3e', 'CvDRuNm', 'wMzswvK', 'sqpcLXRdUq', 'uulcVmocqa', 'xuqoWQSTDrG3sW', 'q14DWPmI', 'c8ovyJddGa', 't3jPu29Uz05HBwu', 'q8orWONdJr8', 'mtpdO8kxkKqHnCotaCosesXmWRvNCCkZW7i2W4C2W7LMWQNdISkg', 'WPxcVCkKWOSHWPNcSCo6sSk0beZdH8kQomkXcmkOW6mxFSoVyCkbW4RcOJnkW55XW6/cMa', 'zgvJB2rL', 'W7dcSaGvWQy', 'WRP2WRDvWQi', 'zMvnCem', 'gmkxW6u', 'W43cVwJdUCknWPuD', 'odFdP8koDa', 'WOBdL8koWRaw', 'W4pdRCoLW5S', 'W6FcTmoSfCkGkHi', 'EctcPZpdMW', 'zNn1uKW', 'W7dcGCoylCkn', 'WPnbW73cHSoy', 'rMLSzuHHC2G', 'W6NdUvddGSoxhK1m', 'b8oJndP3uq', 'D25jvha', 'ugDbt1K', 'WQNdSmkdWRGB', 'yCk/W5pdHCo4WR8', 'u27cUYlcMSkKWO8L', 'zhvYyxrPB24', 'tw96AwXSys81lJaGkfDPBMrVD3mGtLqGmtaUmdSGv2LUnJq7ihG2ncKGqxbWBgvxzwjlAxqVntm3lJm2icHlsfrntcWGBgLRzsbhzwnRBYKGq2HYB21LlZeWnI4WlJaUmcbtywzHCMKVntm3lJm2', 'WQdcVYBdVcOUmJvq', 'WR9PcSosyKRdRvddUmkOp1hcOcddLG', 'y2XHC3nPzNK', 'B8okWRJdPxDMW6SXWRriW4ZdGSongWxdUYSNW6BdT8o6umkLW7igB8kKD0OmWPnQW7mipSkzpqldNCk9xmocW7qSvCklvs3dP2FcVKldNSkSF3TSWRXDAWXGzHnFW7WsD3WuW4HCW6xcLNhdTgZcOCoagSk2W57cGcGhnfXHWOn2uqhdVSkjW4CSW53cIq/dMSkWWQzuWP7cQCkAe8ktWPldJtVdRSk4WQDZW57dSCoBWO0qrKxcTCkrWQHRWPVdSG', 'u1dcVCoX', 'tvvMq3G', 'W5mdWQXTWPq', 'A1T/nSkgpmoIhIJdHCk+pryF', 'imopeW0eW4DUAq', 'bmo8k8olfG', 'rLLOAxC', 'bSoIgbFcMqBcKSo7WRNdQmoIWOSdBSovxCkdBfuUW7lcPxxdLrmJwCobo3r2WPO', 'hSogWQ02s8o3', 'zgxdLCoMBSkp', 'C2XPy2u', 'q2PpEfO', 'ywnJzxnZs2v5', 'BhZdTJVcRCoS', 'rK5WCNq', 'zhf6ELi', 'nZbHmdjHywqXy2u0nJq4ztDKy2e3n2yYywzHn2iXodi', 'rfxcM8kNoG', 'eCo/AHZdSr17W4i', 'iCoheWepW5rTBCoZwG', 'W5dcIeldO8kI', 'u29Uz05HBwu', 'vhnZwfi', 'WRVcQZFdPW', 'WOdcV1FcRG', 'vxDgwNy', 'W5dcOr4lWQu', 'eeNcJmoc', 'WQNcSSkxuG', 'F3lcRZxcMW', 'r1dcRSo1F1buWP0', 'jXSYk8k9', 'krJcJLq', 'BvBcUCocsG', 'Dxb6Axm', 'Ahr0CdOVl2X5CMLJCY5RDwDVDs5JB20VC2vHCMnOp3zLCJ0XjM1HBJ15zxmMy2XPzw50pxbJjMTLExDVCMq9', 'WRRdM8kDWRi', 'p8oskW9m', 'B2zfEfm', 'cmo1bx7dM0XnmW', 'CgXHDa', 'f8k9W7qwxG', 'yxDiD3m', 'W4dcGCkbzCkC', 'xCkPxJpdPNTkpmoiW4S', 'DKnNC0G', 'Ahr0CdOVl21VyMLSzwnKBMjQlMT1z291lMnVBs9HCgKVDJmVCMfUAY9SAxn0p3zLCNnPB249oteWoczWBgf0ptaMC2HVD3r5Cgu9mIzWyxjLBNrPzd0WjMfWAxzLCJ02jMfYzwfFy29Kzt0XjNDPDgHZB25NptaMD2L0Af9YzxnFDgfNpta', 'B8k1W5e', 'CKzpDK4', 'C3bSAxq', 'wGhcGapdMmoL', 's3vhB3uYmdeYltKWmJaTrxHWyw5Ku2vHCMnOtwfUywDLCG', 'jMfJy2vZC2TLEt0', 'W5NcTSo6jSkt', 'ywXIDw1FC2L6ywjSzv9JB3zLCG', 'jaJcP8kyW7XK', 'WRVcKWVdNtu', 'jCo/WPiivW', 'WOXVbmowyfu', 'W4hcIrWOWOW', 'W7tdU2ldKCoa', 'W4tcLa8WWRDYfJS', 'W49dcbldPW', 'ywXIDw0', '5B+I5lYl5ys45l6C5yYEWP/LHRBLIzhOJkq', 'DMzHvva', 'ChvZAa', 'wenWDNi', 'vwf1Eg4', 'tehcJmkDWQL7W6y', 'e8okWR4I', 'w1xcImkEeG', 'WRldOCk7rCoN', 'W4ZdVSoqW4L1', 'xSo0WQFdOr4', 'wxHowuO', 'WR/dLSklWQy+wsy5sSkKWQ3cHImk', 'W4BdTmo4W5vJ', 'WOZdNSkZWPeqWP0H', 'W64pWP96WPetWPJdN8k3rwHUpGNcQCk+', 'wensyM0', 'zSosWQ7dOcawWQuPWR9dW4RdSCobha', 't295t3C', 'W4tdVuxdN8oW', 'gSoBchVdNG', 'WQxcKI/dIqq', 'yxnZAwDU', 'reyBWQaH', 'AvDoAge', 'vfvWyK0', 'Fv3cQmkLjW', 'wNr/iCkaCCoRhG', 'mXZcKf0oWQi', 'ntaXoti0ntbgA1LMuwy', 'WOBcQSkzW67dGCkjW7FcVaJcOa', 'WP7dGCo1zsC', '5yEV55gH5QwZ', 'CvbrtMC', 'WRldLLarqcxcGhCgu8oYWQm', 'oCktWP8aWQ1vW5O', 'xalcGbldMmoGWO0fW74', 'BvdcTmkAla', 'nColcq', 'DMZdVctcU8oIWQCEW5a', 'FgpdG8o9za', 'mtiXmxnnyMHzwa', 'WRxcUthdNri', 'qLbXqMG', 'rKddSSowBq', 'WO7dImoMDrzToG', 't8kYqtJdHW', 'y1ddV8oIsq', 'uCk/tXldM1RdKa', 'h8obde3dRa', 'yxj0D29YAW', 'jmkEimo7W5pcISoAWQFcH8khW7JdPNNcQSozWQRdMCkDg1xcK8oUCfNcPSkXWPODxmkxWOu0W73dLCkbemkoW58eWPzKWOqaW6pcLSkjW5xcOW', 'DLr4BMy', 'y33dTIW', 'W79DcqxdVW', 'zhrQBgG', 'yuxdMSoNAa', 'jdNcRNFdVmoYW5HlWPH/W7WfW5Dyk3VdVmkhaSkehZFdNMxdJaCiWR7cLqZdImkV', 'CeBcRSk2pmkOd0S', 'bCo/ErG', 'D2fuufa', 'b8oeWRK3', 'WOddGSo1vbj8iq', 'y2XPzw50DgLTzq', 'C3rHBMrHCMq', 'y2XPzw50DMvY', 'h8ooWQSNxmoPWO4', 'AgrZA0G', 'WPzJW5xcGmoX', 'dCo3FG3dSq', 'DhjPBq', 'WPtcU8kUWQKNfa', 'AgfZAa', 'W5xcVxhdHSki', 'AgLNAa', 'ywXIDw1FyxvKAw9FAwq', 'ExnrA2m', 'y2fUzgLKyxrLCW', 'bmoFWQSTxCo6WO/cGq', 'q1DJDLe', 'eqKygSkNWQqNESkT', 'W5pcLXOIWQG', 'F3RcPmkKcG', 'W53dKhiVqq', 'sgOnW6VdKq', 'v1zcqMO', '5lUu5Ps55O6X6yEJ54UqWQRcGI/PG7lOV5VPHz/NI57NO4/LRzVLHi/VVBJOVlJLHAdNU4JML63LRl3PHjtNI5RNOlNLJPFLJPhJGje', 'WQbRW4VcLSoCWPC9va', 'WQxdM8kAW5JdU8oNc37dImof', 'D1jjEgS', 'psBdO8kg', 'WQBcILtcOIu', 'cWmwdmkc', 'WOTRW4m', 'l8kTWPizWOy', 'v8kmW5r1W5K', 'mtu0otu0ne53tvDlwG', 'CMfUA25HBwu', 'W5dcQNhdSSkfWPGNlCoHW7v1', 'wwTgAvu', 'WO3dVCk7W7y', 'C3vIC3rYAw5N', 'vxnLCI1bz2vUDa', 'WO3cU8k7', 'wuPRDfm', 'p8oLWOS1wW', 'sM52z2S', 'z0RcVaFcIW', 'W4NdO8k8W5LXW4VdRmkO', 'vu9OELa', 'EhxcV8oExa', 'WRDNcmoqDW', 'jNr5Cgu9ANnVBIzSzxzLBd0', 'sdNcIqddKG', 'jmkbh8oGWR4', 'ySody2niWPxcJCodiCoZiJCpqq', 'BKX6svC', 'qu9iCeC', 'wgr0r3e', 'W7iqWOm', 'E3nPEMv9', 'D1LWsge', 'W4/dQCoWW551W5RdTW', 'WPPVd8owz1lcTa', 'ywXIDw1Uyw1L', 'WRtcRZa', 'B1bADK4', 'C8kRW5JdMmoy', 'WRxcKmksW7RdUmko', 'wg1Ny0u', 'W6BcVCk5u8kM', 'qwXIDw1oyw1L', 'yujqEKy', 'DwpdKCkLWOb5W7dcLG', 'W7pcOSki', 'zSkGW4T3W74', 'W7xdLeKYxW', 'WOhcU8k2', 'ywnJzxnZA2v5', 'fSo3iJj1', 'yxv0Ag9Yx25HBwu', 'Aw5MBW', 'nSkmW73cPd4eWQy0WOjY', 'C0jVCgG', 'yMvOyxzPB3i', 'C2Xcuui', 'BMfTzq', 'D2L0AenYzwrLBNrPywXZ', 'sfbTvfy', 'W64mWOL6', 'mta4ma', 'DgL0Bgu', 'DLO2', 'tLzhAgW', 'W7mqWOz4', 'D2L0Af9YzxnFDgfN', 'WO1LW6ZcV8om', 'a8ogerba', 'zhPtsKS', 'oSoAcrzqWOKSE8oPsh4IWQpcISoMBmoipxNdT2CWW7DJWOpcMSkxBSktiILzW7hdLSkWoSoBqgbljZRcHCovl8ktW6NdGtBdL8ovFW', 'wZBcSJRdQW', 'r0CgWRW', 'mK8ZAKTHmJbhzgTZmeXxB2Pqm2X5n2nR', 'WRZdQ8kRW7VcPSoh', 'k8kHW4lcTCkb', 'svDjsfK', 'lCkgnSo+WOtdUSkgWQpcKSkeW7pdPNNcLSoEWQVcGCktha', 'W5ddOSoyW65G', 'mJCXnKfnuMXSua', 'A2v5', 'sXpcKra', 'jmkVjrOy', 'WOddJSoMvqq', 'kI8Q', 'CgfNzq', 'Ahr0Chm6lY9ZB25NC2vHCMnOlMT1z291lMnVBs9ZB25Nx3nLyxjJAf92mG', 'bY/cS38e', 'yxjLyv9JB2rL', 'W6ldV3dcTMTOBXvaWRTxeL0', 's2LSyKS', 'C29Uz2nVDw50', 't09IquK', 'ugL1z0y', 'dSoIcmonhHyN', 'W73dSwGvqG', 'C8kPW4i', 'zwRdSYdcVmo0WRmCW4yLW71b', 'AvnrAwC', 'WQNcSKBcSYldRCka', 'DunADuW', 'yfBcPmkJ', 'idFcQ3pdUmkPW5qsW5SNW7rlWPe', 'iSoAmZvv', 'mCk1WOKhWOO', 'E3lcRYlcQG', 'W7NdP3NdJmo3', 'DmkmW4PDW4W', 'W51ZksxdNW', 'zmk5W4xdNW', 'gmkRaCoZWRG', 'zgvMyxvSDa', 'g8o5nc4Z', 'bmoRg0xcM0pdKmkAW7JcTCk7', 'rLzoq3G', 'se5bDMi', 'DMfSDwu', 'BMvLzf9OyxnOx29MzNnLDa', 'W4/cJbOZW740xY/dLZbpWRuVW4yyW73cVcBdVghcJwldHbBdVIhdNbpcMb3cIhpcTSo1Amkll8o3WRZdQHL2Age', 'DfuKlCkl', 'u1fgAwXLsgfZAa', 'sXFcGXddHmoTWPu', 'sgfgvNu', 'W7ZdUMhdG8oikePp', 'W6DZarJdG8oM', 'Dg90ywW', 'rCk7tHy', 'EN4TWPaK', 'wmk1W60qdmoyrqC+brziWPJcUqhdLb7cHa7cSmo0', 'WQdcV2lcKdO', 'Bg9Hza', 'qvtcU8oJzvza', 'Aw50CM8', 'WPFdUSkoWOit', 'lHxdNSkFEW', 'nmkDWPWr', 'WQhcS0xcOsO', '54cl6zs25QwE5y6D', 'Bwv0Ag9K', 'wCk1xG', 'hCkyW5mRvq', 'CNOKW47dKG', 'cdGkomkK', 'WRfQWQfNWRO', 'C3rHDhvZ', 'ct/cH3yL', 'vhvQvLe', 'AK9MDLu', 'weDrtMS', 'CMX1EgW', 'rvrfqK0', 'WOZcV1dcPW', 'eSotWROIv8o/WQlcLSoocCkAW6pcVCkJFJddLu9JWPRdV8oHWQahWO/cJCkrgL5mxmkEWRvaoSk1aLTv', 'uh3dRW', 'W7NdT3FdLW', 'WQRcL8k2y1O', 'W6NdUxFdL8oj', 'wKxcImkq', 'nSkYW5VcLSk6', 'AgvHzgvYCW', 'xCkXyHJdUq', 'W6/dISoRW7bj', 'W5VcKSoyfSk4', 'yMDbzLi', 'Ahr0CdOVl21ZzwfYy2GUA3vNB3uUy29Tl2fWAs92mY9ZzwfYy2GVywXIDw0', 'WOLAWQzDWOm', '5A+85ywL5PE26zE05zkm5Q2m5y2v5AsN5Bcp5PYj5ywZ77Ym6k+36icq5B+d562j5B6f', 'r2fgBKy', 'hSkQW53cJSkC', 'CgBdGmo9yW', 'WRdcMmkBW6JdVSkk', 'w1ZcJmkEWRPSW7a', 'WRJcQ8kgt29VWR3dT8ol', 'W54OWOrHWRa', 'zNZcSmofra', 'x0ddT8o8AW', 'WOtcUCkdWQ4b'];
  _0x3692 = function() {
    return _0x4ccddf;
  };
  return _0x3692();
}

function formatMusicItem(_0x458c43) {
  const _0x142936 = _0x5cfaaf,
    _0xc21b2d = _0x211e3b,
    _0xab6b0a = {};
  _0xab6b0a["wJjkH"] = function(_0x31ef12, _0x4172c9) {
    return _0x31ef12 !== _0x4172c9;
  }, _0xab6b0a["vXkFZ"] = function(_0xdd8ab, _0xda10de) {
    return _0xdd8ab !== _0xda10de;
  }, _0xab6b0a["HaFVu"] = function(_0x4e8a86, _0x3bd4e7) {
    return _0x4e8a86 !== _0x3bd4e7;
  }, _0xab6b0a["kIxsB"] = function(_0x3cd3aa, _0x1b2e3f) {
    return _0x3cd3aa !== _0x1b2e3f;
  }, _0xab6b0a["FVjpo"] = function(_0x2b156d, _0x951ff4) {
    return _0x2b156d !== _0x951ff4;
  }, _0xab6b0a["hkKkW"] = "{size}", _0xab6b0a["ofExS"] = "1080", _0xab6b0a["OfTIZ"] = function(_0xaf0e1f, _0x419f3a) {
    return _0xaf0e1f !== _0x419f3a;
  }, _0xab6b0a["HWWYr"] = function(_0x13333e, _0x5641ec) {
    return _0x13333e !== _0x5641ec;
  }, _0xab6b0a["LyVhD"] = function(_0x302180, _0x472b75) {
    return _0x302180 !== _0x472b75;
  }, _0xab6b0a["EJyQB"] = function(_0x17bb7d, _0x355db7) {
    return _0x17bb7d !== _0x355db7;
  };
  const _0x4269d3 = _0xab6b0a;
  var _0x160247, _0x22b191, _0x577143, _0x40cf33, _0x3f66b7, _0x3f86a7, _0x56ef55, _0x43e32b, _0x3ec4ab;
  return {
    'id': _0x4269d3["wJjkH"](_0x40cf33 = _0x458c43["FileHash"], null) && _0x4269d3["vXkFZ"](_0x40cf33, void 0x0) ? _0x40cf33 : _0x458c43["Grp"][0x0]["FileHash"],
    'title': _0x4269d3["vXkFZ"](_0x160247 = _0x458c43["SongName"], null) && _0x4269d3["vXkFZ"](_0x160247, void 0x0) ? _0x160247 : _0x458c43["OriSongName"],
    'artist': _0x4269d3["vXkFZ"](_0x22b191 = _0x458c43["SingerName"], null) && _0x4269d3["HaFVu"](_0x22b191, void 0x0) ? _0x22b191 : _0x458c43["Singers"][0x0]["name"],
    'album': _0x4269d3["vXkFZ"](_0x577143 = _0x458c43["AlbumName"], null) && _0x4269d3["HaFVu"](_0x577143, void 0x0) ? _0x577143 : _0x458c43["Grp"][0x0]["AlbumName"],
    'album_id': _0x4269d3["kIxsB"](_0x3f66b7 = _0x458c43["AlbumID"], null) && _0x4269d3["kIxsB"](_0x3f66b7, void 0x0) ? _0x3f66b7 : _0x458c43["Grp"][0x0]["AlbumID"],
    'album_audio_id': 0x0,
    'duration': _0x458c43["Duration"],
    'artwork': (_0x4269d3["FVjpo"](_0x3f86a7 = _0x458c43["Image"], null) && _0x4269d3["HaFVu"](_0x3f86a7, void 0x0) ? _0x3f86a7 : _0x458c43["Grp"][0x0]["Image"])["replace"](_0x4269d3["hkKkW"], _0x4269d3["ofExS"]),
    '320hash': _0x4269d3["wJjkH"](_0x3ec4ab = _0x458c43["HQFileHash"], null) && _0x4269d3["OfTIZ"](_0x3ec4ab, void 0x0) ? _0x3ec4ab : undefined,
    'sqhash': _0x4269d3["HWWYr"](_0x56ef55 = _0x458c43["SQFileHash"], null) && _0x4269d3["LyVhD"](_0x56ef55, void 0x0) ? _0x56ef55 : undefined,
    'ResFileHash': _0x4269d3["EJyQB"](_0x43e32b = _0x458c43["ResFileHash"], null) && _0x4269d3["kIxsB"](_0x43e32b, void 0x0) ? _0x43e32b : undefined
  };
}

function formatMusicItem2(_0x49ad5f) {
  const _0x1d71da = _0x211e3b,
    _0x120e9b = _0x5cfaaf,
    _0x448d31 = {};
  _0x448d31["pSjso"] = function(_0x12fc79, _0x2c52a7) {
    return _0x12fc79 !== _0x2c52a7;
  }, _0x448d31["iWNha"] = "STUyw", _0x448d31["UOhzP"] = function(_0x656b96, _0x53b43f) {
    return _0x656b96 !== _0x53b43f;
  }, _0x448d31["YJktS"] = function(_0x5739db, _0x361fea) {
    return _0x5739db === _0x361fea;
  }, _0x448d31["vfaUP"] = function(_0x5f51b3, _0x50a02b) {
    return _0x5f51b3 !== _0x50a02b;
  }, _0x448d31["RENbV"] = function(_0x539b90, _0x117cc5) {
    return _0x539b90 === _0x117cc5;
  }, _0x448d31["JknQZ"] = function(_0x452472, _0x58384f) {
    return _0x452472 === _0x58384f;
  }, _0x448d31["Jnvgk"] = function(_0xbc486e, _0x4ccfd1) {
    return _0xbc486e === _0x4ccfd1;
  }, _0x448d31["feMpC"] = function(_0x941c96, _0x2e5db7) {
    return _0x941c96 === _0x2e5db7;
  }, _0x448d31["jKoIB"] = function(_0x4aa3c0, _0x5d680b) {
    return _0x4aa3c0 === _0x5d680b;
  }, _0x448d31["waTPP"] = function(_0x887244, _0x1bd01c) {
    return _0x887244 === _0x1bd01c;
  }, _0x448d31["TssXR"] = function(_0x823d8a, _0x37a607) {
    return _0x823d8a === _0x37a607;
  }, _0x448d31["sPRYX"] = function(_0x2c8dde, _0x5e6e7f) {
    return _0x2c8dde !== _0x5e6e7f;
  }, _0x448d31["FVNCx"] = function(_0x13560d, _0x2c4fd4) {
    return _0x13560d !== _0x2c4fd4;
  }, _0x448d31["PiugF"] = "{size}", _0x448d31["uQcmN"] = "400", _0x448d31["OObAI"] = "320hash";
  const _0x240de8 = _0x448d31;
  var _0xd7f2b4, _0x27f704, _0x1b0b00, _0xd904b4, _0x5d90d4, _0x25b302, _0x31ab70;
  return {
    'id': _0x49ad5f["hash"],
    'title': _0x49ad5f["songname"],
    'artist': _0x240de8["UOhzP"](_0xd7f2b4 = _0x49ad5f["singername"], null) && _0x240de8["vfaUP"](_0xd7f2b4, void 0x0) ? _0xd7f2b4 : (_0x240de8["YJktS"](_0x1b0b00 = _0x240de8["YJktS"](_0x27f704 = _0x49ad5f["authors"], null) || _0x240de8["RENbV"](_0x27f704, void 0x0) ? void 0x0 : _0x27f704["map"](_0x4b9828 => {
      const _0x52b6dc = _0x1d71da,
        _0xf66ef1 = _0x120e9b;
      if (_0x240de8["pSjso"](_0x240de8["iWNha"], _0x240de8["iWNha"])) return;
      else {
        var _0x4db140;
        return _0x240de8["UOhzP"](_0x4db140 = _0x240de8["YJktS"](_0x4b9828, null) || _0x240de8["YJktS"](_0x4b9828, void 0x0) ? void 0x0 : _0x4b9828["author_name"], null) && _0x240de8["pSjso"](_0x4db140, void 0x0) ? _0x4db140 : '';
      }
    }), null) || _0x240de8["JknQZ"](_0x1b0b00, void 0x0) ? void 0x0 : _0x1b0b00["join"](',\x20')) || (_0x240de8["Jnvgk"](_0x25b302 = _0x240de8["YJktS"](_0x5d90d4 = _0x240de8["feMpC"](_0xd904b4 = _0x49ad5f["filename"], null) || _0x240de8["jKoIB"](_0xd904b4, void 0x0) ? void 0x0 : _0xd904b4["split"]('-'), null) || _0x240de8["waTPP"](_0x5d90d4, void 0x0) ? void 0x0 : _0x5d90d4[0x0], null) || _0x240de8["TssXR"](_0x25b302, void 0x0) ? void 0x0 : _0x25b302["trim"]()),
    'album': _0x240de8["sPRYX"](_0x31ab70 = _0x49ad5f["album_name"], null) && _0x240de8["FVNCx"](_0x31ab70, void 0x0) ? _0x31ab70 : _0x49ad5f["remark"],
    'album_id': _0x49ad5f["album_id"],
    'album_audio_id': _0x49ad5f["album_audio_id"],
    'artwork': _0x49ad5f["album_sizable_cover"] ? _0x49ad5f["album_sizable_cover"]["replace"](_0x240de8["PiugF"], _0x240de8["uQcmN"]) : undefined,
    'duration': _0x49ad5f["duration"],
    '320hash': _0x49ad5f[_0x240de8["OObAI"]],
    'sqhash': _0x49ad5f["sqhash"],
    'origin_hash': _0x49ad5f["origin_hash"]
  };
}

function formatImportMusicItem(_0x40c268) {
  const _0x1a9220 = _0x5cfaaf,
    _0x99cd4 = _0x211e3b,
    _0x2f07b7 = {};
  _0x2f07b7["TAUxQ"] = function(_0x2fd961, _0x3cd41b) {
    return _0x2fd961 && _0x3cd41b;
  }, _0x2f07b7["FYhiw"] = function(_0x36b4cc, _0x4f2b03) {
    return _0x36b4cc !== _0x4f2b03;
  }, _0x2f07b7["kgKLX"] = function(_0x433118, _0x5e1eec) {
    return _0x433118 === _0x5e1eec;
  }, _0x2f07b7["wYpHa"] = function(_0x4a41ed, _0x256ced) {
    return _0x4a41ed + _0x256ced;
  }, _0x2f07b7["sBoph"] = function(_0x3db0e1, _0x5b5f1c) {
    return _0x3db0e1 === _0x5b5f1c;
  }, _0x2f07b7["rFOvN"] = function(_0x55caef, _0x1db0e7) {
    return _0x55caef !== _0x1db0e7;
  }, _0x2f07b7["vTxnf"] = function(_0x5a2493, _0x5238c7) {
    return _0x5a2493 !== _0x5238c7;
  }, _0x2f07b7["AOHpG"] = function(_0x2ee1f9, _0x64a1ed) {
    return _0x2ee1f9 === _0x64a1ed;
  }, _0x2f07b7["OoyOw"] = function(_0x23c102, _0x1d930e) {
    return _0x23c102 === _0x1d930e;
  }, _0x2f07b7["ZfRYY"] = function(_0x409ebf, _0x355799) {
    return _0x409ebf === _0x355799;
  }, _0x2f07b7["KilbK"] = function(_0x56827c, _0x83cee8) {
    return _0x56827c === _0x83cee8;
  }, _0x2f07b7["PfGpr"] = "{size}", _0x2f07b7["krAse"] = "400", _0x2f07b7["nvrAl"] = function(_0x43d338, _0x1d63af) {
    return _0x43d338 === _0x1d63af;
  }, _0x2f07b7["fsuRL"] = function(_0x5f4faf, _0x3e1756) {
    return _0x5f4faf === _0x3e1756;
  }, _0x2f07b7["MUfCx"] = function(_0x33a592, _0x4d59c0) {
    return _0x33a592 === _0x4d59c0;
  }, _0x2f07b7["NVGhl"] = function(_0x551dcc, _0x426da9) {
    return _0x551dcc === _0x426da9;
  }, _0x2f07b7["FNprt"] = function(_0xe31432, _0x4408d0) {
    return _0xe31432 === _0x4408d0;
  }, _0x2f07b7["avpNz"] = function(_0x139a99, _0x277798) {
    return _0x139a99 === _0x277798;
  }, _0x2f07b7["WVBBj"] = function(_0x4f9bfd, _0x481485) {
    return _0x4f9bfd === _0x481485;
  }, _0x2f07b7["rgjXc"] = function(_0x226c4f, _0x2673f1) {
    return _0x226c4f === _0x2673f1;
  }, _0x2f07b7["Uauxn"] = function(_0x452474, _0x42186d) {
    return _0x452474 === _0x42186d;
  }, _0x2f07b7["uCZuL"] = function(_0x4dd1bb, _0x328c0a) {
    return _0x4dd1bb === _0x328c0a;
  }, _0x2f07b7["gKlqc"] = function(_0x18cc53, _0x405a8d) {
    return _0x18cc53 && _0x405a8d;
  }, _0x2f07b7["ODvNP"] = "efvXC", _0x2f07b7["slBQB"] = "TujVQ", _0x2f07b7["ClFAK"] = function(_0x2cc3b8, _0x430b4c) {
    return _0x2cc3b8 !== _0x430b4c;
  }, _0x2f07b7["XmgcE"] = function(_0x52f951, _0x314dae) {
    return _0x52f951 + _0x314dae;
  }, _0x2f07b7["RTXKn"] = function(_0x267552, _0x149b72) {
    return _0x267552 === _0x149b72;
  }, _0x2f07b7["XdtGq"] = function(_0x11b723, _0x48e69c) {
    return _0x11b723 !== _0x48e69c;
  }, _0x2f07b7["ychMd"] = function(_0x5dba8b, _0x23171c) {
    return _0x5dba8b !== _0x23171c;
  }, _0x2f07b7["ybRmN"] = function(_0x29d616, _0x40ea54) {
    return _0x29d616 === _0x40ea54;
  }, _0x2f07b7["qWkRs"] = function(_0x29f626, _0x1d24c7) {
    return _0x29f626 === _0x1d24c7;
  }, _0x2f07b7["XGQNk"] = function(_0x530244, _0x34dce0) {
    return _0x530244 === _0x34dce0;
  }, _0x2f07b7["UwFZv"] = function(_0x47841a, _0x2bf969) {
    return _0x47841a === _0x2bf969;
  }, _0x2f07b7["wnITp"] = function(_0x48dc5d, _0x474be9) {
    return _0x48dc5d === _0x474be9;
  }, _0x2f07b7["rluxl"] = function(_0x4be81e, _0xdd9859) {
    return _0x4be81e === _0xdd9859;
  }, _0x2f07b7["ETEBM"] = function(_0x295d72, _0x30ee69) {
    return _0x295d72 === _0x30ee69;
  };
  const _0x3f35a5 = _0x2f07b7;
  var _0xdd77ed, _0x40b762, _0xb53bf0, _0x3e2de3, _0x589ed9, _0x1586dc, _0x3f1d8e;
  let _0xb203eb = _0x40c268["name"];
  const _0x2fe860 = _0x40c268["singername"];
  if (_0x3f35a5["gKlqc"](_0x2fe860, _0xb203eb)) {
    if (_0x3f35a5["WVBBj"](_0x3f35a5["ODvNP"], _0x3f35a5["slBQB"])) {
      var _0x507c52, _0x3b1d9f, _0x144230, _0x563212, _0x487eb5, _0x5e3b83, _0x2d8fbe;
      let _0x340347 = _0x45c2df["name"];
      const _0x5d2656 = _0x5803db["singername"];
      if (_0x3f35a5["TAUxQ"](_0x5d2656, _0x340347)) {
        const _0x2fe35e = _0x340347["indexOf"](_0x5d2656);
        _0x3f35a5["FYhiw"](_0x2fe35e, -0x1) && (_0x340347 = _0x3f35a5["kgKLX"](_0x507c52 = _0x340347["substring"](_0x3f35a5["wYpHa"](_0x3f35a5["wYpHa"](_0x2fe35e, _0x5d2656["length"]), 0x2)), null) || _0x3f35a5["sBoph"](_0x507c52, void 0x0) ? void 0x0 : _0x507c52["trim"]()), !_0x340347 && (_0x340347 = _0x5d2656);
      }
      const _0x1f66c4 = _0x27732a["relate_goods"];
      return {
        'id': _0x2e80d0["hash"],
        'title': _0x340347,
        'artist': _0x5d2656,
        'album': _0x3f35a5["rFOvN"](_0x3b1d9f = _0x4819eb["albumname"], null) && _0x3f35a5["vTxnf"](_0x3b1d9f, void 0x0) ? _0x3b1d9f : '',
        'album_id': _0x5754cd["album_id"],
        'album_audio_id': _0x573858["album_audio_id"],
        'artwork': _0x3f35a5["AOHpG"](_0x563212 = _0x3f35a5["AOHpG"](_0x144230 = _0x3f35a5["OoyOw"](_0xc2b2b0, null) || _0x3f35a5["ZfRYY"](_0xe59eac, void 0x0) ? void 0x0 : _0x45492e["info"], null) || _0x3f35a5["KilbK"](_0x144230, void 0x0) ? void 0x0 : _0x144230["image"], null) || _0x3f35a5["OoyOw"](_0x563212, void 0x0) ? void 0x0 : _0x563212["replace"](_0x3f35a5["PfGpr"], _0x3f35a5["krAse"]),
        '320hash': _0x3f35a5["nvrAl"](_0x487eb5 = _0x3f35a5["fsuRL"](_0x1f66c4, null) || _0x3f35a5["MUfCx"](_0x1f66c4, void 0x0) ? void 0x0 : _0x1f66c4[0x1], null) || _0x3f35a5["NVGhl"](_0x487eb5, void 0x0) ? void 0x0 : _0x487eb5["hash"],
        'sqhash': _0x3f35a5["FNprt"](_0x5e3b83 = _0x3f35a5["avpNz"](_0x1f66c4, null) || _0x3f35a5["WVBBj"](_0x1f66c4, void 0x0) ? void 0x0 : _0x1f66c4[0x2], null) || _0x3f35a5["rgjXc"](_0x5e3b83, void 0x0) ? void 0x0 : _0x5e3b83["hash"],
        'origin_hash': _0x3f35a5["Uauxn"](_0x2d8fbe = _0x3f35a5["uCZuL"](_0x1f66c4, null) || _0x3f35a5["rgjXc"](_0x1f66c4, void 0x0) ? void 0x0 : _0x1f66c4[0x3], null) || _0x3f35a5["NVGhl"](_0x2d8fbe, void 0x0) ? void 0x0 : _0x2d8fbe["hash"]
      };
    } else {
      const _0x2017b1 = _0xb203eb["indexOf"](_0x2fe860);
      _0x3f35a5["ClFAK"](_0x2017b1, -0x1) && (_0xb203eb = _0x3f35a5["Uauxn"](_0xdd77ed = _0xb203eb["substring"](_0x3f35a5["wYpHa"](_0x3f35a5["XmgcE"](_0x2017b1, _0x2fe860["length"]), 0x2)), null) || _0x3f35a5["RTXKn"](_0xdd77ed, void 0x0) ? void 0x0 : _0xdd77ed["trim"]()), !_0xb203eb && (_0xb203eb = _0x2fe860);
    }
  }
  const _0x3dcbe4 = _0x40c268["relate_goods"];
  return {
    'id': _0x40c268["hash"],
    'title': _0xb203eb,
    'artist': _0x2fe860,
    'album': _0x3f35a5["XdtGq"](_0x40b762 = _0x40c268["albumname"], null) && _0x3f35a5["ychMd"](_0x40b762, void 0x0) ? _0x40b762 : '',
    'album_id': _0x40c268["album_id"],
    'album_audio_id': _0x40c268["album_audio_id"],
    'artwork': _0x3f35a5["OoyOw"](_0x3e2de3 = _0x3f35a5["OoyOw"](_0xb53bf0 = _0x3f35a5["WVBBj"](_0x40c268, null) || _0x3f35a5["ybRmN"](_0x40c268, void 0x0) ? void 0x0 : _0x40c268["info"], null) || _0x3f35a5["FNprt"](_0xb53bf0, void 0x0) ? void 0x0 : _0xb53bf0["image"], null) || _0x3f35a5["WVBBj"](_0x3e2de3, void 0x0) ? void 0x0 : _0x3e2de3["replace"](_0x3f35a5["PfGpr"], _0x3f35a5["krAse"]),
    '320hash': _0x3f35a5["fsuRL"](_0x589ed9 = _0x3f35a5["avpNz"](_0x3dcbe4, null) || _0x3f35a5["qWkRs"](_0x3dcbe4, void 0x0) ? void 0x0 : _0x3dcbe4[0x1], null) || _0x3f35a5["Uauxn"](_0x589ed9, void 0x0) ? void 0x0 : _0x589ed9["hash"],
    'sqhash': _0x3f35a5["XGQNk"](_0x1586dc = _0x3f35a5["UwFZv"](_0x3dcbe4, null) || _0x3f35a5["wnITp"](_0x3dcbe4, void 0x0) ? void 0x0 : _0x3dcbe4[0x2], null) || _0x3f35a5["rluxl"](_0x1586dc, void 0x0) ? void 0x0 : _0x1586dc["hash"],
    'origin_hash': _0x3f35a5["fsuRL"](_0x3f1d8e = _0x3f35a5["avpNz"](_0x3dcbe4, null) || _0x3f35a5["fsuRL"](_0x3dcbe4, void 0x0) ? void 0x0 : _0x3dcbe4[0x3], null) || _0x3f35a5["ETEBM"](_0x3f1d8e, void 0x0) ? void 0x0 : _0x3f1d8e["hash"]
  };
}
const _0x1da549 = {};
_0x1da549["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/106.0.0.0 Safari/537.36", _0x1da549["Accept"] = "*/*", _0x1da549["Accept-Encoding"] = "gzip, deflate", _0x1da549["Accept-Language"] = "zh-CN,zh;q=0.9";
const headers = _0x1da549;
async function searchMusic(_0x86fb96, _0x1aeba5) {
  const _0x25873c = _0x211e3b,
    _0x3e2753 = _0x5cfaaf,
    _0x2b0c43 = {};
  _0x2b0c43["cKSOd"] = "https://songsearch.kugou.com/song_search_v2", _0x2b0c43["awHws"] = "WebFilter", _0x2b0c43["DoEXR"] = function(_0x5a756c, _0x3c295c) {
    return _0x5a756c >= _0x3c295c;
  }, _0x2b0c43["jYEOG"] = function(_0x98bb92, _0x417334) {
    return _0x98bb92 * _0x417334;
  };
  const _0x1f8ea5 = _0x2b0c43,
    _0xfdcd1e = (await axios_1["default"]["get"](_0x1f8ea5["cKSOd"], {
      'headers': headers,
      'params': {
        'keyword': _0x86fb96,
        'page': _0x1aeba5,
        'pagesize': pageSize,
        'userid': 0x0,
        'clientver': '',
        'platform': _0x1f8ea5["awHws"],
        'filter': 0x2,
        'iscorrection': 0x1,
        'privilege_filter': 0x0,
        'area_code': 0x1
      }
    }))["data"],
    _0x476209 = _0xfdcd1e["data"]["lists"]["map"](formatMusicItem);
  return {
    'isEnd': _0x1f8ea5["DoEXR"](_0x1f8ea5["jYEOG"](_0x1aeba5, pageSize), _0xfdcd1e["data"]["total"]),
    'data': _0x476209
  };
}

function _0xf53d(_0x41d582, _0x39111f) {
  _0x41d582 = _0x41d582 - 0x67;
  const _0x3692ff = _0x3692();
  let _0x3630c4 = _0x3692ff[_0x41d582];
  if (_0xf53d['nkAocC'] === undefined) {
    var _0x15d69d = function(_0x34f1fc) {
      const _0xf53dfd = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/=';
      let _0xad12db = '',
        _0x4a9959 = '';
      for (let _0x1915e1 = 0x0, _0x4591e8, _0x334000, _0x12a2d4 = 0x0; _0x334000 = _0x34f1fc['charAt'](_0x12a2d4++); ~_0x334000 && (_0x4591e8 = _0x1915e1 % 0x4 ? _0x4591e8 * 0x40 + _0x334000 : _0x334000, _0x1915e1++ % 0x4) ? _0xad12db += String['fromCharCode'](0xff & _0x4591e8 >> (-0x2 * _0x1915e1 & 0x6)) : 0x0) {
        _0x334000 = _0xf53dfd['indexOf'](_0x334000);
      }
      for (let _0x273479 = 0x0, _0x464551 = _0xad12db['length']; _0x273479 < _0x464551; _0x273479++) {
        _0x4a9959 += '%' + ('00' + _0xad12db['charCodeAt'](_0x273479)['toString'](0x10))['slice'](-0x2);
      }
      return decodeURIComponent(_0x4a9959);
    };
    const _0x41301b = function(_0x71998a, _0x1de6d3) {
      let _0x770dd4 = [],
        _0x36c17d = 0x0,
        _0x28339f, _0x398b06 = '';
      _0x71998a = _0x15d69d(_0x71998a);
      let _0x764d89;
      for (_0x764d89 = 0x0; _0x764d89 < 0x100; _0x764d89++) {
        _0x770dd4[_0x764d89] = _0x764d89;
      }
      for (_0x764d89 = 0x0; _0x764d89 < 0x100; _0x764d89++) {
        _0x36c17d = (_0x36c17d + _0x770dd4[_0x764d89] + _0x1de6d3['charCodeAt'](_0x764d89 % _0x1de6d3['length'])) % 0x100, _0x28339f = _0x770dd4[_0x764d89], _0x770dd4[_0x764d89] = _0x770dd4[_0x36c17d], _0x770dd4[_0x36c17d] = _0x28339f;
      }
      _0x764d89 = 0x0, _0x36c17d = 0x0;
      for (let _0x38983f = 0x0; _0x38983f < _0x71998a['length']; _0x38983f++) {
        _0x764d89 = (_0x764d89 + 0x1) % 0x100, _0x36c17d = (_0x36c17d + _0x770dd4[_0x764d89]) % 0x100, _0x28339f = _0x770dd4[_0x764d89], _0x770dd4[_0x764d89] = _0x770dd4[_0x36c17d], _0x770dd4[_0x36c17d] = _0x28339f, _0x398b06 += String['fromCharCode'](_0x71998a['charCodeAt'](_0x38983f) ^ _0x770dd4[(_0x770dd4[_0x764d89] + _0x770dd4[_0x36c17d]) % 0x100]);
      }
      return _0x398b06;
    };
    _0xf53d['rPxriq'] = _0x41301b, _0xf53d['ZocQwd'] = {}, _0xf53d['nkAocC'] = !![];
  }
  const _0xc2cfdf = _0x3692ff[0x0],
    _0x2aa111 = _0x41d582 + _0xc2cfdf,
    _0x459f4b = _0xf53d['ZocQwd'][_0x2aa111];
  return !_0x459f4b ? (_0xf53d['YVlwGF'] === undefined && (_0xf53d['YVlwGF'] = !![]), _0x3630c4 = _0xf53d['rPxriq'](_0x3630c4, _0x39111f), _0xf53d['ZocQwd'][_0x2aa111] = _0x3630c4) : _0x3630c4 = _0x459f4b, _0x3630c4;
}
async function searchAlbum(_0x218beb, _0x2171e5) {
  const _0x304cb3 = _0x211e3b,
    _0x4f69e2 = _0x5cfaaf,
    _0x194d5c = {};
  _0x194d5c["ZgpRF"] = function(_0x520b9c, _0x52f350) {
    return _0x520b9c === _0x52f350;
  }, _0x194d5c["dzSJK"] = function(_0x59fd72, _0x509d82) {
    return _0x59fd72 === _0x509d82;
  }, _0x194d5c["JQSHe"] = "{size}", _0x194d5c["vCgsH"] = "400", _0x194d5c["ehjov"] = "http://msearch.kugou.com/api/v3/search/album", _0x194d5c["vnEdd"] = function(_0x5a5412, _0x154219) {
    return _0x5a5412 >= _0x154219;
  }, _0x194d5c["TUpbM"] = function(_0x34b691, _0x19f639) {
    return _0x34b691 * _0x19f639;
  };
  const _0x43863b = _0x194d5c,
    _0x52b36d = {};
  _0x52b36d["version"] = 0x2394, _0x52b36d["iscorrection"] = 0x1, _0x52b36d["highlight"] = 'em', _0x52b36d["plat"] = 0x0, _0x52b36d["keyword"] = _0x218beb, _0x52b36d["pagesize"] = 0x14, _0x52b36d["page"] = _0x2171e5, _0x52b36d["sver"] = 0x2, _0x52b36d["with_res_tag"] = 0x0;
  const _0x46991b = {};
  _0x46991b["headers"] = headers, _0x46991b["params"] = _0x52b36d;
  const _0x3d02fe = (await axios_1["default"]["get"](_0x43863b["ehjov"], _0x46991b))["data"],
    _0x7eafda = _0x3d02fe["data"]["info"]["map"](_0x4704f8 => {
      const _0x5998fe = _0x304cb3,
        _0x1df5f2 = _0x4f69e2;
      var _0x2acf72, _0x23c980;
      return {
        'id': _0x4704f8["albumid"],
        'artwork': _0x43863b["ZgpRF"](_0x2acf72 = _0x4704f8["imgurl"], null) || _0x43863b["dzSJK"](_0x2acf72, void 0x0) ? void 0x0 : _0x2acf72["replace"](_0x43863b["JQSHe"], _0x43863b["vCgsH"]),
        'artist': _0x4704f8["singername"],
        'title': (0x0, cheerio_1["load"])(_0x4704f8["albumname"])["text"](),
        'description': _0x4704f8["intro"],
        'date': _0x43863b["ZgpRF"](_0x23c980 = _0x4704f8["publishtime"], null) || _0x43863b["dzSJK"](_0x23c980, void 0x0) ? void 0x0 : _0x23c980["slice"](0x0, 0xa)
      };
    });
  return {
    'isEnd': _0x43863b["vnEdd"](_0x43863b["TUpbM"](_0x2171e5, 0x14), _0x3d02fe["data"]["total"]),
    'data': _0x7eafda
  };
}

function _0x3630(_0x41d582, _0x39111f) {
  _0x41d582 = _0x41d582 - 0x67;
  const _0x3692ff = _0x3692();
  let _0x3630c4 = _0x3692ff[_0x41d582];
  if (_0x3630['UAllQY'] === undefined) {
    var _0x15d69d = function(_0x41301b) {
      const _0x34f1fc = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/=';
      let _0xf53dfd = '',
        _0xad12db = '';
      for (let _0x4a9959 = 0x0, _0x1915e1, _0x4591e8, _0x334000 = 0x0; _0x4591e8 = _0x41301b['charAt'](_0x334000++); ~_0x4591e8 && (_0x1915e1 = _0x4a9959 % 0x4 ? _0x1915e1 * 0x40 + _0x4591e8 : _0x4591e8, _0x4a9959++ % 0x4) ? _0xf53dfd += String['fromCharCode'](0xff & _0x1915e1 >> (-0x2 * _0x4a9959 & 0x6)) : 0x0) {
        _0x4591e8 = _0x34f1fc['indexOf'](_0x4591e8);
      }
      for (let _0x12a2d4 = 0x0, _0x273479 = _0xf53dfd['length']; _0x12a2d4 < _0x273479; _0x12a2d4++) {
        _0xad12db += '%' + ('00' + _0xf53dfd['charCodeAt'](_0x12a2d4)['toString'](0x10))['slice'](-0x2);
      }
      return decodeURIComponent(_0xad12db);
    };
    _0x3630['BShHgA'] = _0x15d69d, _0x3630['naJCik'] = {}, _0x3630['UAllQY'] = !![];
  }
  const _0xc2cfdf = _0x3692ff[0x0],
    _0x2aa111 = _0x41d582 + _0xc2cfdf,
    _0x459f4b = _0x3630['naJCik'][_0x2aa111];
  return !_0x459f4b ? (_0x3630c4 = _0x3630['BShHgA'](_0x3630c4), _0x3630['naJCik'][_0x2aa111] = _0x3630c4) : _0x3630c4 = _0x459f4b, _0x3630c4;
}
async function searchMusicSheet(_0x34556b, _0x45d382) {
  const _0x15c28f = _0x211e3b,
    _0x563d2f = _0x5cfaaf,
    _0x45dad7 = {};
  _0x45dad7["UXunO"] = "http://mobilecdn.kugou.com/api/v3/search/special", _0x45dad7["hdskH"] = "json", _0x45dad7["MolPL"] = function(_0x457678, _0x33c381) {
    return _0x457678 >= _0x33c381;
  }, _0x45dad7["KAiCg"] = function(_0x442715, _0x6dbc57) {
    return _0x442715 * _0x6dbc57;
  };
  const _0xd0ab4c = _0x45dad7,
    _0x2a4061 = (await axios_1["default"]["get"](_0xd0ab4c["UXunO"], {
      'headers': headers,
      'params': {
        'format': _0xd0ab4c["hdskH"],
        'keyword': _0x34556b,
        'page': _0x45d382,
        'pagesize': pageSize,
        'showtype': 0x1
      }
    }))["data"],
    _0x476f99 = _0x2a4061["data"]["info"]["map"](_0xfe1db2 => ({
      'title': _0xfe1db2["specialname"],
      'createAt': _0xfe1db2["publishtime"],
      'description': _0xfe1db2["intro"],
      'artist': _0xfe1db2["nickname"],
      'coverImg': _0xfe1db2["imgurl"],
      'gid': _0xfe1db2["gid"],
      'playCount': _0xfe1db2["playcount"],
      'id': _0xfe1db2["specialid"],
      'worksNum': _0xfe1db2["songcount"]
    }));
  return {
    'isEnd': _0xd0ab4c["MolPL"](_0xd0ab4c["KAiCg"](_0x45d382, pageSize), _0x2a4061["data"]["total"]),
    'data': _0x476f99
  };
}
const _0x577536 = {};
_0x577536["low"] = "standard", _0x577536["standard"] = "exhigh", _0x577536["high"] = "lossless", _0x577536["super"] = "hires";
const qualityLevels = _0x577536;
async function getMediaSource(_0x124408, _0x41148c) {
  const _0x193bce = _0x5cfaaf,
    _0x4a1fee = _0x211e3b,
    _0x3bc8a9 = (await axios_1["default"]["get"]("https://music.haitangw.cc/kgqq1/kg.php?id=" + _0x124408['id'] + "&type=json&level=" + qualityLevels[_0x41148c]))["data"],
    _0x9e2bab = {};
  return _0x9e2bab["url"] = _0x3bc8a9["data"]["url"], _0x9e2bab;
}
async function getTopLists() {
  const _0x1a49b5 = _0x5cfaaf,
    _0xea2ce3 = _0x211e3b,
    _0x4500f0 = {};
  _0x4500f0["hYORk"] = function(_0x60184a, _0x27060d) {
    return _0x60184a === _0x27060d;
  }, _0x4500f0["iSQig"] = function(_0x10f990, _0x47e32b) {
    return _0x10f990 === _0x47e32b;
  }, _0x4500f0["HPmTV"] = "{size}", _0x4500f0["YkFiU"] = "400", _0x4500f0["HFzJY"] = function(_0x50895c, _0x5dd007) {
    return _0x50895c === _0x5dd007;
  }, _0x4500f0["bgAfR"] = function(_0x440e, _0x3ad9a7) {
    return _0x440e === _0x3ad9a7;
  }, _0x4500f0["nLzIW"] = function(_0x2c0304, _0x4b9bfa) {
    return _0x2c0304 === _0x4b9bfa;
  }, _0x4500f0["KpusC"] = function(_0x3ad957, _0x840efa) {
    return _0x3ad957 === _0x840efa;
  }, _0x4500f0["TaCxw"] = function(_0x323278, _0x55bb93) {
    return _0x323278 === _0x55bb93;
  }, _0x4500f0["fXIkb"] = function(_0x1b390e, _0x4baab5) {
    return _0x1b390e === _0x4baab5;
  }, _0x4500f0["mWEgr"] = "http://mobilecdnbj.kugou.com/api/v3/rank/list?version=9108&plat=0&showtype=2&parentid=0&apiver=6&area_code=1&withsong=0&with_res_tag=0", _0x4500f0["cvAgx"] = "热门榜单", _0x4500f0["rZUpW"] = "特色音乐榜", _0x4500f0["PbmMt"] = "全球榜", _0x4500f0["IWIHY"] = function(_0x5da5bd, _0x3dd51d) {
    return _0x5da5bd !== _0x3dd51d;
  }, _0x4500f0["GaFnF"] = function(_0x5cfe74, _0x43d613) {
    return _0x5cfe74 === _0x43d613;
  }, _0x4500f0["TizLv"] = "YxNYJ", _0x4500f0["dqzzR"] = "egrLL";
  const _0x1753bd = _0x4500f0,
    _0x16c75b = {};
  _0x16c75b["headers"] = headers;
  const _0x444ac7 = (await axios_1["default"]["get"](_0x1753bd["mWEgr"], _0x16c75b))["data"]["data"]["info"],
    _0x387159 = {};
  _0x387159["title"] = _0x1753bd["cvAgx"], _0x387159["data"] = [];
  const _0x1e917c = {};
  _0x1e917c["title"] = _0x1753bd["rZUpW"], _0x1e917c["data"] = [];
  const _0x4d99cc = {};
  _0x4d99cc["title"] = _0x1753bd["PbmMt"], _0x4d99cc["data"] = [];
  const _0x2c2a9f = [_0x387159, _0x1e917c, _0x4d99cc],
    _0x2a5db8 = {};
  _0x2a5db8["title"] = '其他', _0x2a5db8["data"] = [];
  const _0x53da0d = _0x2a5db8;
  return _0x444ac7["forEach"](_0x26e144 => {
    const _0x8aeeac = _0xea2ce3,
      _0x287b9d = _0x1a49b5;
    var _0x37e577, _0xa824f, _0xab7254, _0x5cb58b;
    if (_0x1753bd["hYORk"](_0x26e144["classify"], 0x1) || _0x1753bd["hYORk"](_0x26e144["classify"], 0x2)) _0x2c2a9f[0x0]["data"]["push"]({
      'id': _0x26e144["rankid"],
      'description': _0x26e144["intro"],
      'coverImg': _0x1753bd["iSQig"](_0x37e577 = _0x26e144["imgurl"], null) || _0x1753bd["hYORk"](_0x37e577, void 0x0) ? void 0x0 : _0x37e577["replace"](_0x1753bd["HPmTV"], _0x1753bd["YkFiU"]),
      'title': _0x26e144["rankname"]
    });
    else {
      if (_0x1753bd["iSQig"](_0x26e144["classify"], 0x3) || _0x1753bd["HFzJY"](_0x26e144["classify"], 0x5)) _0x2c2a9f[0x1]["data"]["push"]({
        'id': _0x26e144["rankid"],
        'description': _0x26e144["intro"],
        'coverImg': _0x1753bd["iSQig"](_0xa824f = _0x26e144["imgurl"], null) || _0x1753bd["bgAfR"](_0xa824f, void 0x0) ? void 0x0 : _0xa824f["replace"](_0x1753bd["HPmTV"], _0x1753bd["YkFiU"]),
        'title': _0x26e144["rankname"]
      });
      else _0x1753bd["HFzJY"](_0x26e144["classify"], 0x4) ? _0x2c2a9f[0x2]["data"]["push"]({
        'id': _0x26e144["rankid"],
        'description': _0x26e144["intro"],
        'coverImg': _0x1753bd["HFzJY"](_0xab7254 = _0x26e144["imgurl"], null) || _0x1753bd["nLzIW"](_0xab7254, void 0x0) ? void 0x0 : _0xab7254["replace"](_0x1753bd["HPmTV"], _0x1753bd["YkFiU"]),
        'title': _0x26e144["rankname"]
      }) : _0x53da0d["data"]["push"]({
        'id': _0x26e144["rankid"],
        'description': _0x26e144["intro"],
        'coverImg': _0x1753bd["KpusC"](_0x5cb58b = _0x26e144["imgurl"], null) || _0x1753bd["nLzIW"](_0x5cb58b, void 0x0) ? void 0x0 : _0x5cb58b["replace"](_0x1753bd["HPmTV"], _0x1753bd["YkFiU"]),
        'title': _0x26e144["rankname"]
      });
    }
  }), _0x1753bd["IWIHY"](_0x53da0d["data"]["length"], 0x0) && (_0x1753bd["GaFnF"](_0x1753bd["TizLv"], _0x1753bd["dqzzR"]) ? _0x3b72a1["data"]["push"]({
    'id': _0x201171["rankid"],
    'description': _0x1ec517["intro"],
    'coverImg': _0x1753bd["TaCxw"](_0x575079 = _0x3627a5["imgurl"], null) || _0x1753bd["fXIkb"](_0x27ee08, void 0x0) ? void 0x0 : _0x4b850f["replace"](_0x1753bd["HPmTV"], _0x1753bd["YkFiU"]),
    'title': _0x562185["rankname"]
  }) : _0x2c2a9f["push"](_0x53da0d)), _0x2c2a9f;
}
async function getTopListDetail(_0x39608f) {
  const _0x4b62eb = _0x211e3b,
    _0x9e0769 = _0x5cfaaf,
    _0x5952c2 = {};
  _0x5952c2["headers"] = headers;
  const _0x3a9d74 = await axios_1["default"]["get"]("http://mobilecdnbj.kugou.com/api/v3/rank/song?version=9108&ranktype=0&plat=0&pagesize=100&area_code=1&page=1&volid=35050&rankid=" + _0x39608f['id'] + "&with_res_tag=0", _0x5952c2);
  return Object["assign"](Object["assign"]({}, _0x39608f), {
    'musicList': _0x3a9d74["data"]["data"]["info"]["map"](formatMusicItem2)
  });
}
async function getLyricDownload(_0x176473) {
  const _0x1c8626 = _0x211e3b,
    _0x473318 = _0x5cfaaf,
    _0x494130 = {};
  _0x494130["fqrkH"] = "expand_search_manager.cpp:852736169:451", _0x494130["VltDq"] = "KuGou2012-9020-ExpandSearchManager", _0x494130["FmYvd"] = "get", _0x494130["jeiSd"] = "XSRF-TOKEN";
  const _0x33d2d9 = _0x494130,
    _0x36daf = {};
  _0x36daf["KG-RC"] = 0x1, _0x36daf["KG-THash"] = _0x33d2d9["fqrkH"], _0x36daf["User-Agent"] = _0x33d2d9["VltDq"];
  const _0x2211df = {};
  _0x2211df["url"] = "http://lyrics.kugou.com/download?ver=1&client=pc&id=" + _0x176473['id'] + "&accesskey=" + _0x176473["accessKey"] + "&fmt=lrc&charset=utf8", _0x2211df["headers"] = _0x36daf, _0x2211df["method"] = _0x33d2d9["FmYvd"], _0x2211df["xsrfCookieName"] = _0x33d2d9["jeiSd"], _0x2211df["withCredentials"] = !![];
  const _0x3ee12a = (await (0x0, axios_1["default"])(_0x2211df))["data"];
  return {
    'rawLrc': he["decode"](CryptoJs["enc"]["Base64"]["parse"](_0x3ee12a["content"])["toString"](CryptoJs["enc"]["Utf8"]))
  };
}
async function getLyric(_0x57a5a0) {
  const _0x51eefc = _0x211e3b,
    _0x22dc87 = _0x5cfaaf,
    _0x46e858 = {
      'ggnoI': "expand_search_manager.cpp:852736169:451",
      'CWcvQ': "KuGou2012-9020-ExpandSearchManager",
      'PgAOY': "get",
      'iXPGh': "XSRF-TOKEN",
      'ysQkc': function(_0x4e203d, _0x37aba6) {
        return _0x4e203d(_0x37aba6);
      }
    },
    _0x3b3752 = {};
  _0x3b3752["KG-RC"] = 0x1, _0x3b3752["KG-THash"] = _0x46e858["ggnoI"], _0x3b3752["User-Agent"] = _0x46e858["CWcvQ"];
  const _0x400eb1 = {};
  _0x400eb1["url"] = "http://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=" + _0x57a5a0["title"] + "&hash=" + _0x57a5a0['id'] + "&timelength=" + _0x57a5a0["duration"], _0x400eb1["headers"] = _0x3b3752, _0x400eb1["method"] = _0x46e858["PgAOY"], _0x400eb1["xsrfCookieName"] = _0x46e858["iXPGh"], _0x400eb1["withCredentials"] = !![];
  const _0x25d586 = (await (0x0, axios_1["default"])(_0x400eb1))["data"],
    _0x5c3e12 = _0x25d586["candidates"][0x0],
    _0x36fb38 = {};
  return _0x36fb38['id'] = _0x5c3e12['id'], _0x36fb38["accessKey"] = _0x5c3e12["accesskey"], await _0x46e858["ysQkc"](getLyricDownload, _0x36fb38);
}
async function getAlbumInfo(_0x3d345a, _0x106480 = 0x1) {
  const _0x56565b = _0x5cfaaf,
    _0x2e8676 = _0x211e3b,
    _0xfcfb73 = {};
  _0xfcfb73["XCpvr"] = function(_0x447fa7, _0x25e988) {
    return _0x447fa7 !== _0x25e988;
  }, _0xfcfb73["zhhKi"] = function(_0x1662d8, _0x2d6b22) {
    return _0x1662d8 !== _0x2d6b22;
  }, _0xfcfb73["wRIxk"] = "http://mobilecdn.kugou.com/api/v3/album/song", _0xfcfb73["BPqBh"] = function(_0x58fcd2, _0x2d1ebf) {
    return _0x58fcd2 >= _0x2d1ebf;
  }, _0xfcfb73["aBPzF"] = function(_0x234417, _0x519e9f) {
    return _0x234417 * _0x519e9f;
  };
  const _0x1bb63f = _0xfcfb73,
    _0x5c62ad = {};
  _0x5c62ad["version"] = 0x2394, _0x5c62ad["albumid"] = _0x3d345a['id'], _0x5c62ad["plat"] = 0x0, _0x5c62ad["pagesize"] = 0x64, _0x5c62ad["area_code"] = 0x1, _0x5c62ad["page"] = _0x106480, _0x5c62ad["with_res_tag"] = 0x0;
  const _0x3c6f4d = {};
  _0x3c6f4d["params"] = _0x5c62ad;
  const _0x147976 = (await axios_1["default"]["get"](_0x1bb63f["wRIxk"], _0x3c6f4d))["data"];
  return {
    'isEnd': _0x1bb63f["BPqBh"](_0x1bb63f["aBPzF"](_0x106480, 0x64), _0x147976["data"]["total"]),
    'albumItem': {
      'worksNum': _0x147976["data"]["total"]
    },
    'musicList': _0x147976["data"]["info"]["map"](_0xc8a97a => {
      const _0x5bac48 = _0x56565b,
        _0xd37398 = _0x2e8676;
      var _0x112138;
      const [_0x10b6a2, _0x4b4205] = _0xc8a97a["filename"]["split"]('-');
      return {
        'id': _0xc8a97a["hash"],
        'title': _0x4b4205["trim"](),
        'artist': _0x10b6a2["trim"](),
        'album': _0x1bb63f["XCpvr"](_0x112138 = _0xc8a97a["album_name"], null) && _0x1bb63f["zhhKi"](_0x112138, void 0x0) ? _0x112138 : _0xc8a97a["remark"],
        'album_id': _0xc8a97a["album_id"],
        'album_audio_id': _0xc8a97a["album_audio_id"],
        'artwork': _0x3d345a["artwork"],
        '320hash': _0xc8a97a["HQFileHash"],
        'sqhash': _0xc8a97a["SQFileHash"],
        'origin_hash': _0xc8a97a['id']
      };
    })
  };
}
async function importMusicSheet(_0xd2a984) {
  const _0x2821a9 = _0x5cfaaf,
    _0x45a5b8 = _0x211e3b,
    _0x4ade6b = {};
  _0x4ade6b["RwdPR"] = ".mp3", _0x4ade6b["NTpdj"] = "audio", _0x4ade6b["tDWKZ"] = function(_0x2ab853, _0x1289fe) {
    return _0x2ab853 === _0x1289fe;
  }, _0x4ade6b["SutuG"] = function(_0x146352, _0x4cc34d) {
    return _0x146352 === _0x4cc34d;
  }, _0x4ade6b["NmnoX"] = "21511157a05844bd085308bc76ef3343", _0x4ade6b["XCRbm"] = "36164c4015e704673c588ee202b9ecb8", _0x4ade6b["upzis"] = "70a02aad1ce4648e7dca77f2afa7b182", _0x4ade6b["IUuEA"] = "381d7062030e8a5a94cfbe50bfe65433", _0x4ade6b["jOfvU"] = function(_0x5e8204, _0x26ec61) {
    return _0x5e8204 === _0x26ec61;
  }, _0x4ade6b["WHfVb"] = "PZzTV", _0x4ade6b["HNAvb"] = "CjOxZ", _0x4ade6b["QMyUH"] = "play", _0x4ade6b["UvexB"] = "10112", _0x4ade6b["ZQgaM"] = "2O3jKa20Gdks0LWojP3ly7ck", _0x4ade6b["oPZvN"] = "media.store.kugou.com";
  const _0x1edcee = _0x4ade6b;
  var _0x574bb2;
  let _0x41c9ec = _0x1edcee["tDWKZ"](_0x574bb2 = _0xd2a984["match"](/^(?:.*?)(\d+)(?:.*?)$/), null) || _0x1edcee["SutuG"](_0x574bb2, void 0x0) ? void 0x0 : _0x574bb2[0x1],
    _0x8fb429 = [];
  if (!_0x41c9ec) return;
  const _0x18ee66 = {};
  _0x18ee66["appid"] = 0x3e9, _0x18ee66["clientver"] = 0x233c, _0x18ee66["mid"] = _0x1edcee["NmnoX"], _0x18ee66["clienttime"] = 0x262efa1f, _0x18ee66["key"] = _0x1edcee["XCRbm"], _0x18ee66["data"] = _0x41c9ec;
  let _0x37d3ef = await axios_1["default"]["post"]("http://t.kugou.com/command/", _0x18ee66);
  if (_0x1edcee["SutuG"](_0x37d3ef["status"], 0xc8) && _0x1edcee["tDWKZ"](_0x37d3ef["data"]["status"], 0x1)) {
    let _0x3da604 = _0x37d3ef["data"]["data"];
    const _0x337d9a = {};
    _0x337d9a['id'] = _0x3da604["info"]['id'], _0x337d9a["type"] = 0x3, _0x337d9a["userid"] = _0x3da604["info"]["userid"], _0x337d9a["collect_type"] = _0x3da604["info"]["collect_type"], _0x337d9a["page"] = 0x1, _0x337d9a["pagesize"] = _0x3da604["info"]["count"];
    const _0x59479e = {};
    _0x59479e["appid"] = 0x3e9, _0x59479e["clientver"] = 0x2780, _0x59479e["mid"] = _0x1edcee["upzis"], _0x59479e["clienttime"] = 0x2b0c31ed, _0x59479e["key"] = _0x1edcee["IUuEA"], _0x59479e["data"] = _0x337d9a;
    let _0x13a1f8 = await axios_1["default"]["post"]("http://www2.kugou.kugou.com/apps/kucodeAndShare/app/", _0x59479e);
    if (_0x1edcee["tDWKZ"](_0x13a1f8["status"], 0xc8) && _0x1edcee["SutuG"](_0x13a1f8["data"]["status"], 0x1)) {
      if (_0x1edcee["jOfvU"](_0x1edcee["WHfVb"], _0x1edcee["HNAvb"])) _0x56e2a3 = _0xa40a8d["data"]["data"]["map"](_0x13fb03);
      else {
        let _0x4a8222 = [];
        _0x13a1f8["data"]["data"]["forEach"](_0x2c9110 => {
          const _0xf2443f = _0x45a5b8,
            _0x218535 = _0x2821a9;
          _0x4a8222["push"]({
            'album_audio_id': 0x0,
            'album_id': '0',
            'hash': _0x2c9110["hash"],
            'id': 0x0,
            'name': _0x2c9110["filename"]["replace"](_0x1edcee["RwdPR"], ''),
            'page_id': 0x0,
            'type': _0x1edcee["NTpdj"]
          });
        });
        const _0x500c8a = {};
        _0x500c8a["appid"] = 0x3e9, _0x500c8a["area_code"] = '1', _0x500c8a["behavior"] = _0x1edcee["QMyUH"], _0x500c8a["clientver"] = _0x1edcee["UvexB"], _0x500c8a["dfid"] = _0x1edcee["ZQgaM"], _0x500c8a["mid"] = _0x1edcee["upzis"], _0x500c8a["need_hash_offset"] = 0x1, _0x500c8a["relate"] = 0x1, _0x500c8a["resource"] = _0x4a8222, _0x500c8a["token"] = '', _0x500c8a["userid"] = '0', _0x500c8a["vip"] = 0x0;
        let _0x588aee = _0x500c8a;
        const _0x30eab4 = {};
        _0x30eab4["x-router"] = _0x1edcee["oPZvN"];
        const _0x8344ae = {};
        _0x8344ae["headers"] = _0x30eab4;
        var _0x19cbae = await axios_1["default"]["post"]("https://gateway.kugou.com/v2/get_res_privilege/lite?appid=1001&clienttime=1668883879&clientver=10112&dfid=2O3jKa20Gdks0LWojP3ly7ck&mid=70a02aad1ce4648e7dca77f2afa7b182&userid=390523108&uuid=92691C6246F86F28B149BAA1FD370DF1", _0x588aee, _0x8344ae);
        _0x1edcee["jOfvU"](_0x13a1f8["status"], 0xc8) && _0x1edcee["SutuG"](_0x13a1f8["data"]["status"], 0x1) && (_0x8fb429 = _0x19cbae["data"]["data"]["map"](formatImportMusicItem));
      }
    }
  }
  return _0x8fb429;
}
const _0x831d9e = {};
_0x831d9e["importMusicSheet"] = ["仅支持酷狗APP通过酷狗码导入，输入纯数字酷狗码即可。", "导入时间和歌单大小有关，请耐心等待"], module["exports"] = {
  'platform': "元力KG",
  'version': "1.2.0",
  'author': "微信公众号:元力菌",
  'srcUrl': "https://13413.kstore.vip/yuanli/kg.js",
  'cacheControl': "no-cache",
  'description': '',
  'primaryKey': ['id', "album_id", "album_audio_id"],
  'hints': _0x831d9e,
  'supportedSearchType': ["music", "album", "sheet"],
  async 'search'(_0x511c8f, _0x50360b, _0x1abe77) {
    const _0x372da5 = _0x211e3b,
      _0x11ec3d = _0x5cfaaf,
      _0x14d61c = {
        'ERxCw': function(_0x3fa480, _0x4b0db3) {
          return _0x3fa480 === _0x4b0db3;
        },
        'IrKSd': "music",
        'dtjlh': function(_0x3995f7, _0x13bf09, _0x18b0f7) {
          return _0x3995f7(_0x13bf09, _0x18b0f7);
        },
        'JCCVl': function(_0x73fac6, _0x2df7d7) {
          return _0x73fac6 === _0x2df7d7;
        },
        'xLXvq': "album",
        'waAdX': function(_0x4854c2, _0x1a9258) {
          return _0x4854c2 === _0x1a9258;
        },
        'Opudr': "sheet",
        'qPQNg': function(_0x5203af, _0x5a5b8e, _0x49fd31) {
          return _0x5203af(_0x5a5b8e, _0x49fd31);
        }
      };
    if (_0x14d61c["ERxCw"](_0x1abe77, _0x14d61c["IrKSd"])) return await _0x14d61c["dtjlh"](searchMusic, _0x511c8f, _0x50360b);
    else {
      if (_0x14d61c["JCCVl"](_0x1abe77, _0x14d61c["xLXvq"])) return await _0x14d61c["dtjlh"](searchAlbum, _0x511c8f, _0x50360b);
      else {
        if (_0x14d61c["waAdX"](_0x1abe77, _0x14d61c["Opudr"])) return await _0x14d61c["qPQNg"](searchMusicSheet, _0x511c8f, _0x50360b);
      }
    }
  },
  'getMediaSource': getMediaSource,
  'getTopLists': getTopLists,
  'getLyric': getLyric,
  'getTopListDetail': getTopListDetail,
  'getAlbumInfo': getAlbumInfo,
  'importMusicSheet': importMusicSheet
};