.class public Lcom/renwuzhi/app/MainActivity;
.super Landroid/app/Activity;

.field public web:Landroid/webkit/WebView;
.field public fileCallback:Landroid/webkit/ValueCallback;
.field public pendingBytes:[B

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Landroid/app/Activity;-><init>()V
    return-void
.end method

.method protected onCreate(Landroid/os/Bundle;)V
    .registers 7
    invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V

    new-instance v0, Landroid/widget/FrameLayout;
    invoke-direct {v0, p0}, Landroid/widget/FrameLayout;-><init>(Landroid/content/Context;)V
    const/4 v1, 0x1
    invoke-virtual {v0, v1}, Landroid/widget/FrameLayout;->setFitsSystemWindows(Z)V
    const v2, -0xb0e18
    invoke-virtual {v0, v2}, Landroid/widget/FrameLayout;->setBackgroundColor(I)V

    new-instance v2, Landroid/webkit/WebView;
    invoke-direct {v2, p0}, Landroid/webkit/WebView;-><init>(Landroid/content/Context;)V
    iput-object v2, p0, Lcom/renwuzhi/app/MainActivity;->web:Landroid/webkit/WebView;
    const v3, -0xb0e18
    invoke-virtual {v2, v3}, Landroid/webkit/WebView;->setBackgroundColor(I)V

    invoke-virtual {v2}, Landroid/webkit/WebView;->getSettings()Landroid/webkit/WebSettings;
    move-result-object v3
    invoke-virtual {v3, v1}, Landroid/webkit/WebSettings;->setJavaScriptEnabled(Z)V
    invoke-virtual {v3, v1}, Landroid/webkit/WebSettings;->setDomStorageEnabled(Z)V
    invoke-virtual {v3, v1}, Landroid/webkit/WebSettings;->setAllowFileAccess(Z)V
    invoke-virtual {v3, v1}, Landroid/webkit/WebSettings;->setAllowContentAccess(Z)V
    invoke-virtual {v3, v1}, Landroid/webkit/WebSettings;->setAllowFileAccessFromFileURLs(Z)V
    invoke-virtual {v3, v1}, Landroid/webkit/WebSettings;->setAllowUniversalAccessFromFileURLs(Z)V
    const/16 v4, 0x64
    invoke-virtual {v3, v4}, Landroid/webkit/WebSettings;->setTextZoom(I)V

    new-instance v3, Lcom/renwuzhi/app/Chrome;
    invoke-direct {v3, p0}, Lcom/renwuzhi/app/Chrome;-><init>(Lcom/renwuzhi/app/MainActivity;)V
    invoke-virtual {v2, v3}, Landroid/webkit/WebView;->setWebChromeClient(Landroid/webkit/WebChromeClient;)V

    new-instance v3, Landroid/webkit/WebViewClient;
    invoke-direct {v3}, Landroid/webkit/WebViewClient;-><init>()V
    invoke-virtual {v2, v3}, Landroid/webkit/WebView;->setWebViewClient(Landroid/webkit/WebViewClient;)V

    new-instance v3, Lcom/renwuzhi/app/Bridge;
    invoke-direct {v3, p0}, Lcom/renwuzhi/app/Bridge;-><init>(Lcom/renwuzhi/app/MainActivity;)V
    const-string v4, "RenwuzhiAndroid"
    invoke-virtual {v2, v3, v4}, Landroid/webkit/WebView;->addJavascriptInterface(Ljava/lang/Object;Ljava/lang/String;)V

    invoke-virtual {v0, v2}, Landroid/widget/FrameLayout;->addView(Landroid/view/View;)V
    invoke-virtual {p0, v0}, Lcom/renwuzhi/app/MainActivity;->setContentView(Landroid/view/View;)V

    const-string v3, "file:///android_asset/www/index.html"
    invoke-virtual {v2, v3}, Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V
    return-void
.end method

.method public onBackPressed()V
    .registers 2
    iget-object v0, p0, Lcom/renwuzhi/app/MainActivity;->web:Landroid/webkit/WebView;
    invoke-virtual {v0}, Landroid/webkit/WebView;->canGoBack()Z
    move-result v0
    if-eqz v0, :cond_super
    iget-object v0, p0, Lcom/renwuzhi/app/MainActivity;->web:Landroid/webkit/WebView;
    invoke-virtual {v0}, Landroid/webkit/WebView;->goBack()V
    return-void
    :cond_super
    invoke-super {p0}, Landroid/app/Activity;->onBackPressed()V
    return-void
.end method

.method protected onActivityResult(IILandroid/content/Intent;)V
    .registers 8
    const/4 v0, 0x1
    if-ne p1, v0, :cond_save
    iget-object v1, p0, Lcom/renwuzhi/app/MainActivity;->fileCallback:Landroid/webkit/ValueCallback;
    if-eqz v1, :cond_done
    invoke-static {p2, p3}, Landroid/webkit/WebChromeClient$FileChooserParams;->parseResult(ILandroid/content/Intent;)[Landroid/net/Uri;
    move-result-object v2
    invoke-interface {v1, v2}, Landroid/webkit/ValueCallback;->onReceiveValue(Ljava/lang/Object;)V
    const/4 v2, 0x0
    iput-object v2, p0, Lcom/renwuzhi/app/MainActivity;->fileCallback:Landroid/webkit/ValueCallback;
    goto :cond_done

    :cond_save
    const/4 v0, 0x2
    if-ne p1, v0, :cond_done
    const/4 v0, -0x1
    if-ne p2, v0, :cond_fail
    if-eqz p3, :cond_fail
    invoke-virtual {p3}, Landroid/content/Intent;->getData()Landroid/net/Uri;
    move-result-object v1
    if-eqz v1, :cond_fail
    iget-object v2, p0, Lcom/renwuzhi/app/MainActivity;->pendingBytes:[B
    if-eqz v2, :cond_fail
    :try_start
    invoke-virtual {p0}, Lcom/renwuzhi/app/MainActivity;->getContentResolver()Landroid/content/ContentResolver;
    move-result-object v3
    invoke-virtual {v3, v1}, Landroid/content/ContentResolver;->openOutputStream(Landroid/net/Uri;)Ljava/io/OutputStream;
    move-result-object v3
    invoke-virtual {v3, v2}, Ljava/io/OutputStream;->write([B)V
    invoke-virtual {v3}, Ljava/io/OutputStream;->close()V
    :try_end
    .catch Ljava/lang/Exception; {:try_start .. :try_end} :cond_fail
    const-string v0, "true"
    goto :cond_notify

    :cond_fail
    const-string v0, "false"

    :cond_notify
    const/4 v1, 0x0
    iput-object v1, p0, Lcom/renwuzhi/app/MainActivity;->pendingBytes:[B
    new-instance v1, Ljava/lang/StringBuilder;
    const-string v2, "window.__androidSaved&&window.__androidSaved("
    invoke-direct {v1, v2}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V
    invoke-virtual {v1, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    const-string v2, ")"
    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v1
    iget-object v2, p0, Lcom/renwuzhi/app/MainActivity;->web:Landroid/webkit/WebView;
    const/4 v3, 0x0
    invoke-virtual {v2, v1, v3}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V

    :cond_done
    return-void
.end method
