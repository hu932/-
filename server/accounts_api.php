<?php
/**
 * 账号管家 - 共享接口（带注册登录）
 * 部署后把本文件地址填到 App 的「共享接口地址」即可。
 *
 * 接口：POST JSON，body 带 act；也支持 ?act=xxx
 *   注册 / 登录（无需 token）：
 *     act=register {username, password} -> {username, token}
 *     act=login    {username, password} -> {username, token}
 *   数据接口（body 需带 token）：
 *     act=list / sync / clear / mark_used / dates
 *
 * 返回统一格式：{ok:true, data:..., msg:"", updated_at:"Y-m-d H:i:s"}
 * 数据存储：
 *   users.json           {username: {pass:hash, token, createdAt}}
 *   accounts_data.json   {accounts: {username: [account, ...]}}
 */

header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: POST, GET, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type');

if (($_SERVER['REQUEST_METHOD'] ?? '') === 'OPTIONS') {
    http_response_code(204);
    exit;
}

$dataFile  = __DIR__ . '/accounts_data.json';
$usersFile = __DIR__ . '/users.json';

function now_ms() {
    return (int)(microtime(true) * 1000);
}

function read_json($file, $default) {
    if (!is_file($file)) return $default;
    $raw = @file_get_contents($file);
    $d = json_decode((string)$raw, true);
    return is_array($d) ? $d : $default;
}

function write_json($file, $data) {
    $fp = @fopen($file, 'c');
    if ($fp) {
        if (flock($fp, LOCK_EX)) {
            ftruncate($fp, 0);
            fwrite($fp, json_encode($data, JSON_UNESCAPED_UNICODE));
            fflush($fp);
            flock($fp, LOCK_UN);
        }
        fclose($fp);
    }
    return $data;
}

function load_accounts() {
    global $dataFile;
    $d = read_json($dataFile, []);
    return ['accounts' => isset($d['accounts']) && is_array($d['accounts']) ? $d['accounts'] : []];
}

function save_accounts($store) {
    global $dataFile;
    return write_json($dataFile, $store);
}

function load_users() {
    global $usersFile;
    $d = read_json($usersFile, []);
    return isset($d['users']) && is_array($d['users']) ? $d['users'] : $d;
}

function save_users($users) {
    global $usersFile;
    return write_json($usersFile, ['users' => $users]);
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
        'createdAt' => isset($a['createdAt']) ? (int)$a['createdAt'] : now_ms(),
        'usage' => isset($a['usage']) && is_array($a['usage'])
            ? array_values(array_filter(array_map('intval', $a['usage']), function ($v) { return $v > 0; }))
            : [],
    ];
}

function auth_user($users, $token) {
    $token = (string)$token;
    if ($token === '') return null;
    foreach ($users as $u => $info) {
        if (isset($info['token']) && hash_equals((string)$info['token'], $token)) {
            return $u;
        }
    }
    return null;
}

$body = json_decode((string)file_get_contents('php://input'), true);
if (!is_array($body)) $body = [];
$act = isset($_GET['act']) ? $_GET['act'] : (isset($body['act']) ? $body['act'] : '');

switch ($act) {
    case 'register': {
        $u = trim((string)($body['username'] ?? ''));
        $p = (string)($body['password'] ?? '');
        if (strlen($u) < 2) respond(false, null, '用户名至少 2 个字符');
        if (strlen($p) < 4) respond(false, null, '密码至少 4 位');
        $users = load_users();
        if (isset($users[$u])) respond(false, null, '用户名已存在');
        $token = bin2hex(random_bytes(24));
        $users[$u] = ['pass' => password_hash($p, PASSWORD_DEFAULT), 'token' => $token, 'createdAt' => now_ms()];
        save_users($users);
        respond(true, ['username' => $u, 'token' => $token]);
        break;
    }

    case 'login': {
        $u = trim((string)($body['username'] ?? ''));
        $p = (string)($body['password'] ?? '');
        $users = load_users();
        if (!isset($users[$u]) || !password_verify($p, $users[$u]['pass'])) {
            respond(false, null, '用户名或密码错误');
        }
        $token = isset($users[$u]['token']) ? (string)$users[$u]['token'] : '';
        if ($token === '') {
            $token = bin2hex(random_bytes(24));
            $users[$u]['token'] = $token;
            save_users($users);
        }
        respond(true, ['username' => $u, 'token' => $token]);
        break;
    }

    default: {
        // 数据接口：需要 token
        $users = load_users();
        $me = auth_user($users, isset($body['token']) ? $body['token'] : '');
        if ($me === null) respond(false, null, '未登录或登录已过期');

        $store = load_accounts();
        $mine = isset($store['accounts'][$me]) && is_array($store['accounts'][$me])
            ? $store['accounts'][$me] : [];

        switch ($act) {
            case 'list':
                respond(true, $mine);
                break;

            case 'sync': {
                $incoming = isset($body['accounts']) && is_array($body['accounts']) ? $body['accounts'] : [];
                $removed = isset($body['removed']) && is_array($body['removed']) ? $body['removed'] : [];
                $removedSet = array_flip(array_map('strval', $removed));

                $map = [];
                foreach ($mine as $a) {
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

                $store['accounts'][$me] = array_values($map);
                save_accounts($store);
                respond(true, $store['accounts'][$me]);
                break;
            }

            case 'clear':
                $store['accounts'][$me] = [];
                save_accounts($store);
                respond(true, []);
                break;

            case 'mark_used': {
                $key = isset($body['key']) ? (string)$body['key'] : '';
                $now = now_ms();
                foreach ($mine as $i => $a) {
                    if (key_of($a) === $key) {
                        $mine[$i]['usedAt'] = $now;
                        $mine[$i]['updatedAt'] = $now;
                        $u = isset($mine[$i]['usage']) && is_array($mine[$i]['usage']) ? $mine[$i]['usage'] : [];
                        if (!in_array($now, $u, true)) {
                            $u[] = $now;
                            sort($u);
                        }
                        $mine[$i]['usage'] = $u;
                        $store['accounts'][$me] = $mine;
                        save_accounts($store);
                        break;
                    }
                }
                respond(true, isset($store['accounts'][$me]) ? $store['accounts'][$me] : []);
                break;
            }

            case 'dates': {
                $byDate = [];
                foreach ($mine as $a) {
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
                respond(false, null, '未知操作');
        }
    }
}
