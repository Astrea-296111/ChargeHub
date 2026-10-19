SELECT * FROM charge_order WHERE user_id=(SELECT id FROM iam_user WHERE login_name='perf0000') AND id<9223372036854775807 ORDER BY id DESC LIMIT 20;
