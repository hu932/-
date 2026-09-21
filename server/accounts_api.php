<?php
/**
 * 账号管家 - 共享接口（新）
 * 部署后把本文件地址填到 App 的「共享接口地址」即可（长按顶部同步状态修改）。
 *
 * 接口：POST JSON，body 里带 act；也支持 ?act=xxx
 *   act=list       拉取全部账号
 *   act=sync       {accounts:[...], removed:[...]} 合并后返回权威列表
 *   act=clear      清空全部账号
 *   act=mark_used  {key:"u:xxx"|"i:xxx"} 记录一次使用（usedAt + usage 历史）
 *   act=dates      按创建日期统计数量
 *
 * 返回统一格式：{ok:true, data:[...], msg:"", updated_at:"Y-m-d H:i:s"}
 * 数据存储：同目录 accounts_data.json（无需数据库、无需密码）
 */

header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: POST, GET, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type');

if (($_SERVER['REQUEST_METHOD'] ?? '') === 'OPTIONS') {
    http_response_code(204);
    exit;
}

$dataFile = __DIR__ . '/accounts_data.json';

function load_store() {
    global $dataFile;
    if (!is_file($dataFile)) {
        return ['accounts' => [], 'updated_at' => null];
    }
    $raw = @file_get_contents($dataFile);
    $d = json_decode((string)$raw, true);
    if (!is_array($d)) {
        return ['accounts' => [], 'updated_at' => null];
    }
    return [
        'accounts' => isset($d['accounts']) && is_array($d['accounts']) ? $d['accounts'] : [],
        'updated_at' => isset($d['updated_at']) ? $d['updated_at'] : null,
    ];
}

function save_store($store) {
    global $dataFile;
    $store['updated_at'] = date('Y-m-d H:i:s');
    $fp = @fopen($dataFile, 'c');
    if ($fp) {
        if (flock($fp, LOCK_EX)) {
            ftruncate($fp, 0);
            fwrite($fp, json_encode($store, JSON_UNESCAPED_UNICODE));
            fflush($fp);
            flock($fp, LOCK_UN);
        }
        fclose($fp);
    }
    return $store;
}

function key_of($a) {
    return (!empty($a['uid'])) ? ('u:' . $a['uid']) : ('i:' . ($a['id'] ?? ''));
}

function norm_account($a) {
    if (!is_array($a)) $a = [];
    return [
        'id' => isset($a['id']) ? (string)$a['id'] : '',
        'uid' => isset($a['uid']) ? (string)$a['uid'] : '',
        'cookies' => isset($a['cookies']) && is_array($a['cookies']) ? $a['cookies'] : [],
        'ua' => isset($a['ua']) ? (string)$a['ua'] : '',
        'region' => isset($a['region']) ? (string)$a['region'] : '',
        'usedAt' => isset($a['usedAt']) ? $a['usedAt'] : null,
        'updatedAt' => isset($a['updatedAt']) ? (int)$a['updatedAt'] : 0,
        'createdAt' => isset($a['createdAt']) ? (int)$a['createdAt'] : (int)(microtime(true) * 1000),
        'usage' => isset($a['usage']) && is_array($a['usage'])
            ? array_values(array_filter(array_map('intval', $a['usage']), function ($v) { return $v > 0; }))
            : [],
    ];
}

function respond($ok, $data, $msg = '') {
    echo json_encode([
        'ok' => (bool)$ok,
        'data' => $data,
        'msg' => $msg,
        'updated_at' => date('Y-m-d H:i:s'),
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

$body = json_decode((string)file_get_contents('php://input'), true);
if (!is_array($body)) $body = [];
$act = isset($_GET['act']) ? $_GET['act'] : (isset($body['act']) ? $body['act'] : '');

$store = load_store();

switch ($act) {
    case 'list':
        respond(true, $store['accounts']);
        break;

    case 'sync': {
        $incoming = isset($body['accounts']) && is_array($body['accounts']) ? $body['accounts'] : [];
        $removed = isset($body['removed']) && is_array($body['removed']) ? $body['removed'] : [];
        $removedSet = array_flip(array_map('strval', $removed));

        $map = [];
        foreach ($store['accounts'] as $a) {
            $a = norm_account($a);
            $k = key_of($a);
            if (isset($removedSet[$k])) continue;
            $map[$k] = $a;
        }

        foreach ($incoming as $a) {
            $a = norm_account($a);
            $k = key_of($a);
            if (isset($removedSet[$k])) continue;

            if (isset($map[$k])) {
                // 使用历史合并（去重、升序）；创建时间取更早；updatedAt/usedAt 取更新
                $usage = array_values(array_unique(array_merge($map[$k]['usage'], $a['usage'])));
                sort($usage);
                $a['usage'] = $usage;
                $a['createdAt'] = min($map[$k]['createdAt'], $a['createdAt']);

                if ($a['updatedAt'] < $map[$k]['updatedAt']) {
                    $a['updatedAt'] = $map[$k]['updatedAt'];
                    $a['usedAt'] = $map[$k]['usedAt'];
                } elseif ($a['updatedAt'] === $map[$k]['updatedAt']) {
                    $a['usedAt'] = $map[$k]['usedAt'] ?: $a['usedAt'];
                }
            }
            $map[$k] = $a;
        }

        $store['accounts'] = array_values($map);
        save_store($store);
        respond(true, $store['accounts']);
        break;
    }

    case 'clear':
        $store['accounts'] = [];
        save_store($store);
        respond(true, []);
        break;

    case 'mark_used': {
        $key = isset($body['key']) ? (string)$body['key'] : '';
        $now = (int)(microtime(true) * 1000);
        foreach ($store['accounts'] as $i => $a) {
            if (key_of($a) === $key) {
                $store['accounts'][$i]['usedAt'] = $now;
                $store['accounts'][$i]['updatedAt'] = $now;
                $u = isset($store['accounts'][$i]['usage']) && is_array($store['accounts'][$i]['usage'])
                    ? $store['accounts'][$i]['usage'] : [];
                if (!in_array($now, $u, true)) {
                    $u[] = $now;
                    sort($u);
                }
                $store['accounts'][$i]['usage'] = $u;
                save_store($store);
                break;
            }
        }
        respond(true, $store['accounts']);
        break;
    }

    case 'dates': {
        $byDate = [];
        foreach ($store['accounts'] as $a) {
            $d = date('Y-m-d', intval((isset($a['createdAt']) ? $a['createdAt'] : 0) / 1000));
            $byDate[$d] = isset($byDate[$d]) ? $byDate[$d] + 1 : 1;
        }
        krsort($byDate);
        $out = [];
        foreach ($byDate as $d => $n) {
            $out[] = ['date' => $d, 'count' => $n];
        }
        respond(true, $out);
        break;
    }

    default:
        respond(false, [], '未知操作');
}
