package com.rioikeda.newslisten.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.rioikeda.newslisten.R
import com.rioikeda.newslisten.designsystem.DSSpacing
import com.rioikeda.newslisten.designsystem.NewsListenTheme
import kotlinx.coroutines.launch

/**
 * ログイン画面。ユーザーID とパスワードでログインする。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Auth/LoginView.swift（ユーザーID/パスワード入力・エラー表示）
 * のミラー。Passkey ログインは後続フェーズで実装予定。
 *
 * @param viewModel ログイン ViewModel（AuthViewModel）
 */
@Composable
fun LoginScreen(viewModel: AuthViewModel) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // ViewModel の状態を購読
    val loginErrorMessage by viewModel.loginErrorMessage.collectAsStateWithLifecycle()

    // ログイン処理中か判定（AuthState が Authenticated に遷移するまでは isSubmitting = true）
    var isSubmitting by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(DSSpacing.l),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // タイトル
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(DSSpacing.s))

            // サブタイトル
            Text(
                text = stringResource(R.string.login_subtitle),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(DSSpacing.l))

            // ユーザーID 入力
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.login_username_label)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSubmitting,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                )
            )

            Spacer(modifier = Modifier.height(DSSpacing.m))

            // パスワード 入力
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.login_password_label)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSubmitting,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                )
            )

            Spacer(modifier = Modifier.height(DSSpacing.m))

            // エラーメッセージ
            loginErrorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = DSSpacing.m)
                )
            }

            // ログイン ボタン
            Button(
                onClick = {
                    isSubmitting = true
                    scope.launch {
                        viewModel.login(username, password)
                        // login() 後、authState が Authenticated に遷移したら呼び出し元で画面遷移
                        isSubmitting = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSubmitting && username.isNotEmpty() && password.isNotEmpty()
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(end = DSSpacing.s),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.login_button_label))
                }
            }
        }
    }
}
