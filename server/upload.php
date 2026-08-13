<?php
// NoShorts debug-log upload endpoint.
// The app POSTs key + log; we store it under /noshorts/logs/ and return a
// public URL so Dali can paste the link in chat (no USB needed).
$key = isset($_POST['key']) ? $_POST['key'] : '';
$log = isset($_POST['log']) ? $_POST['log'] : '';

if (!hash_equals('dali951-noshorts', $key)) {
    http_response_code(403);
    die('bad key');
}
if (strlen($log) < 10 || strlen($log) > 50000) {
    http_response_code(400);
    die('log too short or too long');
}

$dir = __DIR__ . '/logs';
if (!is_dir($dir)) {
    @mkdir($dir, 0775, true);
}

$name = 'log-' . date('Ymd-His') . '-' . substr(bin2hex(random_bytes(3)), 0, 6) . '.txt';
if (file_put_contents($dir . '/' . $name, $log) === false) {
    http_response_code(500);
    die('write failed');
}

$proto = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') ? 'https' : 'http';
$host = isset($_SERVER['HTTP_HOST']) ? $_SERVER['HTTP_HOST'] : 'modali.powerpme.com';
header('Content-Type: application/json');
echo json_encode(array('ok' => true, 'url' => $proto . '://' . $host . '/noshorts/logs/' . $name));
