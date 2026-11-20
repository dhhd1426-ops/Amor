.class public Lcom/renwuzhi/app/Chrome;
.super Landroid/webkit/WebChromeClient;

.field final a:Lcom/renwuzhi/app/MainActivity;

.method public constructor <init>(Lcom/renwuzhi/app/MainActivity;)V
    .registers 2
    invoke-direct {p0}, Landroid/webkit/WebChromeClient;-><init>()V
    iput-object p1, p0, Lcom/renwuzhi/app/Chrome;->a:Lcom/renwuzhi/app/MainActivity;
    return-void
.end method

.method public onShowFileChooser(Landroid/webkit/WebView;Landroid/webkit/ValueCallback;Landroid/webkit/WebChromeClient$FileChooserParams;)Z
    .registers 8
    iget-object v0, p0, Lcom/renwuzhi/app/Chrome;->a:Lcom/renwuzhi/app/MainActivity;
    iget-object v1, v0, Lcom/renwuzhi/app/MainActivity;->fileCallback:Landroid/webkit/ValueCallback;
    if-eqz v1, :cond_fresh
    const/4 v2, 0x0
    invoke-interface {v1, v2}, Landroid/webkit/ValueCallback;->onReceiveValue(Ljava/lang/Object;)V
    :cond_fresh
    iput-object p2, v0, Lcom/renwuzhi/app/MainActivity;->fileCallback:Landroid/webkit/ValueCallback;
    new-instance v1, Landroid/content/Intent;
    const-string v2, "android.intent.action.GET_CONTENT"
    invoke-direct {v1, v2}, Landroid/content/Intent;-><init>(Ljava/lang/String;)V
    const-string v2, "android.intent.category.OPENABLE"
    invoke-virtual {v1, v2}, Landroid/content/Intent;->addCategory(Ljava/lang/String;)Landroid/content/Intent;
    const-string v2, "*/*"
    invoke-virtual {v1, v2}, Landroid/content/Intent;->setType(Ljava/lang/String;)Landroid/content/Intent;
    :try_start
    const/4 v2, 0x1
    invoke-virtual {v0, v1, v2}, Lcom/renwuzhi/app/MainActivity;->startActivityForResult(Landroid/content/Intent;I)V
    :try_end
    .catch Landroid/content/ActivityNotFoundException; {:try_start .. :try_end} :cond_none
    const/4 v2, 0x1
    return v2
    :cond_none
    const/4 v2, 0x0
    iput-object v2, v0, Lcom/renwuzhi/app/MainActivity;->fileCallback:Landroid/webkit/ValueCallback;
    return v2
.end method
