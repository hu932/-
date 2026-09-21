<?php
/**
 * 账号管家 - 管理后台
 * 部署到与 accounts_api.php 相同的目录，浏览器访问：http://你的域名/admin.php
 * 功能：查看用户、查看/转移/删除账号、把旧版未分配账号迁移到指定用户。
 * ⚠️ 请修改下面的管理员密码！
 */
$ADMIN_PASSWORD = 'admin123';

session_start();

$dataFile  = __DIR__ . '/accounts_data.json';
$usersFile = __DIR__ . '/users.json';

function now_ms() { return (int)(microtime(true) * 1000); }

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

function load_users() {
    global $usersFile;
    $d = read_json($usersFile, []);
    return isset($d['users']) && is_array($d['users']) ? $d['users'] : $d;
}

function save_users($users) {
    global $usersFile;
    return write_json($usersFile, ['users' => $users]);
}

/**
 * 返回 ['map'=>username=>[账号], 'legacy'=>旧版扁平账号列表]。
 * 兼容三种情况：纯旧版扁平列表、纯新版用户表、以及两者混合（已有用户同步过）。
 * 数字键 -> 旧版扁平账号；字符串键 -> 用户名下的账号列表。
 */
function load_accounts() {
    global $dataFile;
    $d = read_json($dataFile, []);
    $accounts = isset($d['accounts']) && is_array($d['accounts']) ? $d['accounts'] : [];
    $map = [];
    $legacy = [];
    foreach ($accounts as $k => $v) {
        if (is_int($k) || (is_string($k) && ctype_digit($k))) {
            if (is_array($v)) $legacy[] = $v; // 旧版扁平账号（每个 v 是一个账号）
        } else {
            $map[$k] = $v; // 用户名 -> 账号列表
        }
    }
    return ['map' => $map, 'legacy' => $legacy];
}

function save_map($map) {
    global $dataFile;
    return write_json($dataFile, ['accounts' => $map]);
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

function h($s) { return htmlspecialchars((string)$s, ENT_QUOTES, 'UTF-8'); }

function u_count($raw, $u) {
    return isset($raw['map'][$u]) && is_array($raw['map'][$u]) ? count($raw['map'][$u]) : 0;
}

// ---------- 登录 ----------
$err = '';
if (isset($_POST['action']) && $_POST['action'] === 'login') {
    if (hash_equals($ADMIN_PASSWORD, (string)($_POST['password'] ?? ''))) {
        $_SESSION['admin'] = 1;
    } else {
        $err = '密码错误';
    }
}
if (isset($_GET['logout'])) {
    unset($_SESSION['admin']);
    header('Location: ' . basename(__FILE__));
    exit;
}

if (empty($_SESSION['admin'])) {
    render_login($err);
    exit;
}

// ---------- 操作 ----------
$msg = '';
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $action = $_POST['action'] ?? '';
    $users = load_users();
    $raw = load_accounts();

    if ($action === 'migrate_legacy') {
        $target = trim((string)($_POST['target'] ?? ''));
        if (!isset($users[$target])) { $msg = '目标用户不存在'; }
        elseif (empty($raw['legacy'])) { $msg = '没有待迁移的旧账号'; }
        else {
            $map = $raw['map'];
            $map[$target] = array_merge(
                isset($map[$target]) ? $map[$target] : [],
                array_map('norm_account', $raw['legacy'])
            );
            save_map($map);
            $msg = '已迁移 ' . count($raw['legacy']) . ' 个旧账号到用户「' . $target . '」';
        }
    } elseif ($action === 'move') {
        $from = (string)($_POST['from'] ?? '');
        $to = trim((string)($_POST['to'] ?? ''));
        $keys = isset($_POST['keys']) && is_array($_POST['keys']) ? $_POST['keys'] : [];
        if ($from === $to) { $msg = '来源和目标不能相同'; }
        elseif (!isset($users[$from])) { $msg = '来源用户不存在'; }
        elseif (!isset($users[$to])) { $msg = '目标用户不存在'; }
        else {
            $map = $raw['map'];
            $src = isset($map[$from]) ? $map[$from] : [];
            $keep = []; $move = [];
            foreach ($src as $a) {
                if (in_array(key_of($a), $keys, true)) $move[] = $a; else $keep[] = $a;
            }
            $map[$from] = $keep;
            $map[$to] = array_merge(
                isset($map[$to]) ? $map[$to] : [],
                array_map('norm_account', $move)
            );
            save_map($map);
            $msg = '已从「' . $from . '」移动 ' . count($move) . ' 个账号到「' . $to . '」';
        }
    } elseif ($action === 'delete_accounts') {
        $from = (string)($_POST['from'] ?? '');
        $keys = isset($_POST['keys']) && is_array($_POST['keys']) ? $_POST['keys'] : [];
        if (!isset($users[$from])) { $msg = '用户不存在'; }
        else {
            $map = $raw['map'];
            $src = isset($map[$from]) ? $map[$from] : [];
            $keep = []; $del = 0;
            foreach ($src as $a) {
                if (in_array(key_of($a), $keys, true)) { $del++; } else { $keep[] = $a; }
            }
            $map[$from] = $keep;
            save_map($map);
            $msg = '已删除 ' . $del . ' 个账号';
        }
    } elseif ($action === 'delete_user') {
        $u = trim((string)($_POST['user'] ?? ''));
        if (isset($users[$u])) {
            unset($users[$u]);
            save_users($users);
            $map = $raw['map'];
            unset($map[$u]);
            save_map($map);
            $msg = '已删除用户「' . $u . '」及其账号';
        } else {
            $msg = '用户不存在';
        }
    }
}

$users = load_users();
$raw = load_accounts();

if (isset($_GET['view']) && isset($users[$_GET['view']])) {
    render_user($users, $raw, (string)$_GET['view'], $msg);
} else {
    render_dashboard($users, $raw, $msg);
}

// ==================== 页面渲染 ====================

function page_top($title) {
    echo '<!doctype html><html lang="zh"><head><meta charset="utf-8">'
        . '<meta name="viewport" content="width=device-width, initial-scale=1">'
        . '<title>' . h($title) . '</title><style>'
        . 'body{font-family:-apple-system,"Segoe UI","Microsoft YaHei",sans-serif;background:#f4f5f7;margin:0;padding:24px;color:#1c2430}'
        . '.card{background:#fff;border:1px solid #e6e9ee;border-radius:12px;padding:16px;margin-bottom:16px}'
        . 'table{width:100%;border-collapse:collapse}'
        . 'th,td{text-align:left;padding:8px 10px;border-bottom:1px solid #eef1f5;font-size:14px}'
        . 'th{color:#8a94a3;font-weight:600}'
        . 'button,a.btn{display:inline-block;border:0;border-radius:8px;padding:8px 14px;font-size:14px;cursor:pointer;text-decoration:none;color:#fff;background:#2f6bff}'
        . 'button.gray{background:#8a94a3} button.red{background:#e5484d} button.green{background:#12a66a}'
        . 'input,select{border:1px solid #e6e9ee;border-radius:8px;padding:8px 10px;font-size:14px;background:#fafbfc}'
        . '.msg{background:#eefaf4;border:1px solid #d2f0e2;color:#0b7a4b;padding:10px;border-radius:8px;margin-bottom:16px}'
        . '.err{background:#fdf0f0;border:1px solid #f2c8ca;color:#e5484d;padding:10px;border-radius:8px;margin-bottom:16px}'
        . '.warn{background:#fff7e0;border:1px solid #f0dca0;color:#8a6d00;padding:10px;border-radius:8px;margin-bottom:16px}'
        . 'a{color:#2f6bff} .muted{color:#8a94a3;font-size:12px}'
        . '.top{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px}'
        . 'h2{margin:0 0 4px}'
        . '</style></head><body><div style="max-width:900px;margin:0 auto">';
}

function page_bottom() {
    echo '</div></body></html>';
}

function render_login($err) {
    page_top('管理后台登录');
    if ($err) echo '<div class="err">' . h($err) . '</div>';
    echo '<div class="card" style="max-width:360px;margin:80px auto">'
        . '<h2>账号管家 · 管理后台</h2>'
        . '<form method="post">'
        . '<input type="hidden" name="action" value="login">'
        . '<p><input type="password" name="password" placeholder="管理员密码" style="width:100%;box-sizing:border-box"></p>'
        . '<button type="submit" style="width:100%">登 录</button>'
        . '</form></div>';
    page_bottom();
    exit;
}

function render_dashboard($users, $raw, $msg) {
    page_top('管理后台');
    echo '<div class="top"><h2>账号管家 · 管理后台</h2><a class="btn gray" href="?logout=1">退出登录</a></div>';
    if ($msg) echo '<div class="msg">' . h($msg) . '</div>';

    // 旧账号（未分配）
    if (!empty($raw['legacy'])) {
        echo '<div class="card"><h2 style="color:#8a6d00">待迁移的旧账号（' . count($raw['legacy']) . ' 个）</h2>'
            . '<p class="muted">这些是升级账号体系之前存在的账号，尚未归属任何用户。</p>'
            . '<form method="post"><input type="hidden" name="action" value="migrate_legacy">'
            . '迁移到用户：<select name="target">';
        foreach ($users as $u => $info) {
            echo '<option value="' . h($u) . '">' . h($u) . '（' . u_count($raw, $u) . ' 个）</option>';
        }
        echo '</select> <button type="submit" class="green">全部迁移</button></form>';
        echo '<table style="margin-top:10px"><tr><th>UID</th><th>Cookie 数</th><th>创建时间</th></tr>';
        foreach ($raw['legacy'] as $a) {
            echo '<tr><td>' . h($a['uid'] ?? '未识别') . '</td><td>' . count($a['cookies'] ?? []) . '</td>'
                . '<td>' . h(date('Y-m-d H:i', intval(($a['createdAt'] ?? 0) / 1000))) . '</td></tr>';
        }
        echo '</table></div>';
    }

    // 用户列表
    echo '<div class="card"><h2>用户（' . count($users) . ' 个）</h2>'
        . '<table><tr><th>用户名</th><th>账号数</th><th>注册时间</th><th>操作</th></tr>';
    foreach ($users as $u => $info) {
        echo '<tr><td>' . h($u) . '</td><td>' . u_count($raw, $u) . '</td>'
            . '<td>' . h(date('Y-m-d H:i', intval(($info['createdAt'] ?? 0) / 1000))) . '</td>'
            . '<td><a class="btn" href="?view=' . urlencode($u) . '">查看账号</a> '
            . '<form method="post" style="display:inline"><input type="hidden" name="action" value="delete_user">'
            . '<input type="hidden" name="user" value="' . h($u) . '">'
            . '<button type="submit" class="red" onclick="return confirm(\'确定删除用户 ' . h($u) . ' 及其所有账号？\')">删除</button></form></td></tr>';
    }
    echo '</table></div>';
    page_bottom();
    exit;
}

function render_user($users, $raw, $view, $msg) {
    page_top('用户 ' . $view);
    echo '<div class="top"><h2>用户：' . h($view) . '</h2><a class="btn gray" href="' . basename(__FILE__) . '">返回</a></div>';
    if ($msg) echo '<div class="msg">' . h($msg) . '</div>';

    $list = isset($raw['map'][$view]) ? $raw['map'][$view] : [];
    echo '<div class="card"><h2>账号（' . count($list) . ' 个）</h2>';
    if (empty($list)) {
        echo '<p class="muted">该用户暂无账号。</p>';
    } else {
        echo '<form method="post" id="userForm">'
            . '<input type="hidden" name="action" value="move">'
            . '<input type="hidden" name="from" value="' . h($view) . '">'
            . '<table><tr><th><input type="checkbox" onclick="toggleAll(this)"></th><th>UID</th><th>Cookie 数</th><th>已用次数</th><th>创建时间</th></tr>';
        foreach ($list as $a) {
            $k = key_of($a);
            echo '<tr><td><input type="checkbox" name="keys[]" value="' . h($k) . '"></td>'
                . '<td>' . h($a['uid'] ?? '未识别') . '</td>'
                . '<td>' . count($a['cookies'] ?? []) . '</td>'
                . '<td>' . count($a['usage'] ?? []) . '</td>'
                . '<td>' . h(date('Y-m-d H:i', intval(($a['createdAt'] ?? 0) / 1000))) . '</td></tr>';
        }
        echo '</table>';
        // 目标用户下拉
        echo '<div style="margin-top:14px">移动到用户：<select name="to">';
        foreach ($users as $u => $info) {
            if ($u === $view) continue;
            echo '<option value="' . h($u) . '">' . h($u) . '</option>';
        }
        echo '</select> <button type="submit">移动选中</button> ';
        echo '<button type="button" class="red" onclick="delSelected()">删除选中</button></div></form>';
    }
    echo '</div>';

    echo '<script>function toggleAll(cb){var cs=document.querySelectorAll(\'input[name="keys[]"]\');cs.forEach(function(x){x.checked=cb.checked})}'
        . 'function delSelected(){if(!confirm("确定删除选中的账号？"))return;'
        . 'var f=document.getElementById("userForm");f.querySelector(\'input[name="action"]\').value="delete_accounts";f.submit()}</script>';
    page_bottom();
    exit;
}
