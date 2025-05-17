package com.transcale.abajiang

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.*

class MainActivity : ComponentActivity() {
    private lateinit var sharedPreferences: SharedPreferences

    private val BAIDU_APP_ID = "你的 AppID"
    private val BAIDU_SECRET_KEY = "你的密钥"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPreferences = getSharedPreferences("TranslatorAppPrefs", MODE_PRIVATE)

        val savedBackgroundUri = sharedPreferences.getString("background_uri", null)
        val initialBitmap = savedBackgroundUri?.let { uriString ->
            val uri = Uri.parse(uriString)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(this.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(this.contentResolver, uri)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load bitmap from URI: $uriString, error: ${e.message}")
                null
            }
        }

        setContent {
            TranslatorScreen(this, initialBitmap)
        }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray())
        val result = StringBuilder()
        for (byte in bytes) {
            val hex = Integer.toHexString(0xFF and byte.toInt())
            if (hex.length == 1) result.append('0')
            result.append(hex)
        }
        return result.toString()
    }

    suspend fun translateText(text: String, sourceLang: String, targetLang: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val salt = UUID.randomUUID().toString()
                val sign = md5(BAIDU_APP_ID + text + salt + BAIDU_SECRET_KEY)

                val url = "https://fanyi-api.baidu.com/api/trans/vip/translate?" +
                        "q=${URLEncoder.encode(text, "UTF-8")}&" +
                        "from=$sourceLang&" +
                        "to=$targetLang&" +
                        "appid=$BAIDU_APP_ID&" +
                        "salt=$salt&" +
                        "sign=$sign"

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val json = response.body?.string() ?: return@withContext "翻译失败：响应为空"

                val jsonObject = JSONObject(json)
                if (jsonObject.has("error_code")) {
                    val errorCode = jsonObject.getString("error_code")
                    val errorMsg = jsonObject.getString("error_msg")
                    return@withContext if (errorCode == "58001") {
                        "翻译失败：目标语言可能需要企业账号支持（错误码 $errorCode，$errorMsg）"
                    } else {
                        "翻译失败：错误码 $errorCode，$errorMsg"
                    }
                }

                val transResult = jsonObject.getJSONArray("trans_result")
                if (transResult.length() > 0) {
                    transResult.getJSONObject(0).getString("dst")
                } else {
                    "翻译失败：无翻译结果"
                }
            } catch (e: Exception) {
                "翻译失败：${e.message}"
            }
        }
    }
}

@Composable
fun TranslatorScreen(context: Context, initialBitmap: Bitmap?) {
    val configuration = LocalConfiguration.current
    val isSmallScreen = configuration.screenWidthDp < 600
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

    val horizontalPadding = if (isSmallScreen) 12.dp else 24.dp
    val verticalPadding = if (isSmallScreen) 16.dp else 24.dp
    val elementSpacing = if (isSmallScreen) 12.dp else 16.dp
    val fontSize = if (isSmallScreen) 14.sp else 16.sp
    val buttonSize = if (isSmallScreen) 44.dp else 50.dp
    val buttonTextSize = if (isSmallScreen) 16.sp else 18.sp

    var inputText by remember { mutableStateOf("") }
    var resultText by remember { mutableStateOf("") }
    var sourceLang by remember { mutableStateOf("zh") }
    var targetLang by remember { mutableStateOf("en") }
    var backgroundBitmap by remember { mutableStateOf(initialBitmap) }
    var isTranslating by remember { mutableStateOf(false) }

    val languages = listOf(
        "中文 (zh)",
        "英语 (en)",
        "日语 (jp)",
        "韩语 (kor)",
        "法语 (fra)",
        "西班牙语 (spa)",
        "德语 (de)",
        "苗语 (hmn)"
    )
    val languageCodes = listOf("zh", "en", "jp", "kor", "fra", "spa", "de", "hmn")

    val languageMap = languages.mapIndexed { index, language ->
        val code = languageCodes[index]
        val name = language.substringBefore(" (").trim()
        code to name
    }.toMap()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to take persistable URI permission: ${e.message}")
            }

            val bitmap = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load bitmap: ${e.message}")
                null
            }

            if (bitmap != null) {
                backgroundBitmap = bitmap
                val editor = context.getSharedPreferences("TranslatorAppPrefs", Context.MODE_PRIVATE).edit()
                editor.putString("background_uri", uri.toString())
                editor.apply()
                Log.d("MainActivity", "Background URI saved: $uri")
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        if (isGranted) {
            launcher.launch("image/*")
        } else {
            Log.d("MainActivity", "Permission denied")
        }
    }

    LaunchedEffect(inputText, sourceLang, targetLang, isTranslating) {
        if (isTranslating && inputText.isNotEmpty()) {
            val result = (context as MainActivity).translateText(inputText, sourceLang, targetLang)
            resultText = result
            isTranslating = false
        }
    }

    val density = LocalDensity.current
    val screenHeightDp = configuration.screenHeightDp.toFloat()
    val maxHeight: Dp = with(density) {
        (if (isSmallScreen) screenHeightDp * 0.3f else screenHeightDp * 0.4f).dp
    }

    // 动态计算ResultBox高度
    val resultBoxHeight: Dp = remember(resultText, density) {
        val lineCount = resultText.lines().size
        val lineHeight = fontSize.value
        val paddingHeight = 16.dp.value
        val estimatedHeight = (lineCount * lineHeight + paddingHeight).dp
        estimatedHeight.coerceAtMost(maxHeight)
    }

    // 输入框和输出框高度
    val inputBoxHeight = if (isSmallScreen) 120.dp else 150.dp
    val offsetHeight = inputBoxHeight / 2 // 向下移动输入框高度的一半

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (backgroundBitmap == null) Color(0xFFD3D3D3) else Color.Transparent
            )
    ) {
        backgroundBitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "背景图片",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .align(Alignment.TopCenter)
                .padding(top = verticalPadding + offsetHeight, start = horizontalPadding, end = horizontalPadding), // 向下偏移
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 语言选择区域
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = elementSpacing),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (isSmallScreen) Arrangement.SpaceBetween else Arrangement.SpaceEvenly
            ) {
                LanguageTag(
                    text = "源语言 (${languageMap[sourceLang]})",
                    languages = languages,
                    languageCodes = languageCodes,
                    onLanguageSelected = { code -> sourceLang = code },
                    isSmallScreen = isSmallScreen
                )

                IconButton(
                    onClick = {
                        val temp = sourceLang
                        sourceLang = targetLang
                        targetLang = temp
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = "交换语言",
                        tint = Color(0xFFA67BFF),
                        modifier = Modifier.size(if (isSmallScreen) 24.dp else 32.dp)
                    )
                }

                LanguageTag(
                    text = "目标语言 (${languageMap[targetLang]})",
                    languages = languages,
                    languageCodes = languageCodes,
                    onLanguageSelected = { code -> targetLang = code },
                    isSmallScreen = isSmallScreen
                )
            }

            // 输入和结果区域
            if (isLandscape) {
                // 横屏布局
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = elementSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    InputBox(
                        text = inputText,
                        onValueChange = { inputText = it },
                        placeholder = "输入翻译的内容",
                        modifier = Modifier
                            .weight(1f)
                            .height(inputBoxHeight)
                            .padding(end = 8.dp),
                        isSmallScreen = isSmallScreen
                    )

                    ResultBox(
                        result = resultText,
                        modifier = Modifier
                            .weight(1f)
                            .height(inputBoxHeight)
                            .padding(start = 8.dp),
                        isSmallScreen = isSmallScreen
                    )
                }
            } else {
                // 竖屏布局
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = elementSpacing),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    InputBox(
                        text = inputText,
                        onValueChange = { inputText = it },
                        placeholder = "输入翻译的内容",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(inputBoxHeight)
                            .padding(bottom = 8.dp),
                        isSmallScreen = isSmallScreen
                    )

                    ResultBox(
                        result = resultText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(inputBoxHeight),
                        isSmallScreen = isSmallScreen
                    )
                }
            }

            // 翻译按钮
            Button(
                onClick = {
                    if (inputText.isNotEmpty()) {
                        isTranslating = true
                    } else {
                        resultText = "请输入翻译的内容"
                    }
                },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 8.dp)
                    .width(if (isSmallScreen) 100.dp else 120.dp)
                    .height(buttonSize)
                    .clip(RoundedCornerShape(buttonSize / 2)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFA67BFF),
                    contentColor = Color.White
                )
            ) {
                Text("翻译", fontSize = buttonTextSize, fontWeight = FontWeight.Bold)
            }

            // 背景设置和清除按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = {
                        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            android.Manifest.permission.READ_MEDIA_IMAGES
                        } else {
                            android.Manifest.permission.READ_EXTERNAL_STORAGE
                        }
                        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                            launcher.launch("image/*")
                        } else {
                            permissionLauncher.launch(permission)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA67BFF)),
                    modifier = Modifier.height(if (isSmallScreen) 36.dp else 40.dp)
                ) {
                    Text("更换背景", color = Color.White, fontSize = if (isSmallScreen) 12.sp else 14.sp)
                }

                Button(
                    onClick = {
                        inputText = ""
                        resultText = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA67BFF)),
                    modifier = Modifier.height(if (isSmallScreen) 36.dp else 40.dp)
                ) {
                    Text("清除", color = Color.White, fontSize = if (isSmallScreen) 12.sp else 14.sp)
                }

                Button(
                    onClick = {
                        backgroundBitmap = null
                        val editor = context.getSharedPreferences("TranslatorAppPrefs", Context.MODE_PRIVATE).edit()
                        editor.remove("background_uri")
                        editor.apply()
                        Log.d("MainActivity", "Background URI cleared")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA67BFF)),
                    modifier = Modifier.height(if (isSmallScreen) 36.dp else 40.dp)
                ) {
                    Text("还原背景", color = Color.White, fontSize = if (isSmallScreen) 12.sp else 14.sp)
                }
            }
        }
    }
}

@Composable
fun LanguageTag(
    text: String,
    languages: List<String>,
    languageCodes: List<String>,
    onLanguageSelected: (String) -> Unit,
    isSmallScreen: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    val fontSize = if (isSmallScreen) 12.sp else 14.sp

    Box(
        modifier = Modifier
            .background(Color(0xFFA67BFF), RoundedCornerShape(8.dp))
            .padding(horizontal = if (isSmallScreen) 8.dp else 16.dp, vertical = if (isSmallScreen) 4.dp else 8.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            modifier = Modifier.clickable { expanded = true },
            fontSize = fontSize
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFFA67BFF))
        ) {
            languages.forEachIndexed { index, language ->
                DropdownMenuItem(
                    text = { Text(language, color = Color.White, fontSize = fontSize) },
                    onClick = {
                        onLanguageSelected(languageCodes[index])
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun InputBox(
    text: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isSmallScreen: Boolean
) {
    val fontSize = if (isSmallScreen) 14.sp else 16.sp

    TextField(
        value = text,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = Color.Gray, fontSize = fontSize) },
        modifier = modifier
            .clip(RoundedCornerShape(16.dp)) // 添加圆角
            .background(Color(0xFFA67BFF), RoundedCornerShape(16.dp)),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color(0xFFA67BFF),
            unfocusedContainerColor = Color(0xFFA67BFF),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        textStyle = TextStyle(color = Color.White, fontSize = fontSize),
        maxLines = 10
    )
}

@Composable
fun ResultBox(
    result: String,
    modifier: Modifier = Modifier,
    isSmallScreen: Boolean
) {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val fontSize = if (isSmallScreen) 14.sp else 16.sp

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp)) // 确保圆角一致
            .background(Color.White, RoundedCornerShape(16.dp))
            .verticalScroll(state = rememberScrollState())
    ) {
        SelectionContainer {
            Text(
                text = result,
                color = Color.Black,
                modifier = Modifier
                    .padding(8.dp)
                    .align(Alignment.TopStart),
                fontSize = fontSize,
                onTextLayout = { textLayoutResult ->
                    if (textLayoutResult.hasVisualOverflow) {
                        // 内容溢出时启用滚动
                    }
                }
            )
        }
    }
}
