.class public Lcom/renwuzhi/app/Bridge;
.super Ljava/lang/Object;
.implements Ljava/lang/Runnable;

.field final a:Lcom/renwuzhi/app/MainActivity;
.field pending:Landroid/content/Intent;

.method public constructor <init>(Lcom/renwuzhi/app/MainActivity;)V
    .registers 2
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Lcom/renwuzhi/app/Bridge;->a:Lcom/renwuzhi/app/MainActivity;
    return-void
.end method

.method public save(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
    .registers 8
    .annotation runtime Landroid/webkit/JavascriptInterface;
    .end annotation
    const/4 v0, 0x0
    invoke-static {p3, v0}, Landroid/util/Base64;->decode(Ljava/lang/String;I)[B
    move-result-object v0
    iget-object v1, p0, Lcom/renwuzhi/app/Bridge;->a:Lcom/renwuzhi/app/MainActivity;
    iput-object v0, v1, Lcom/renwuzhi/app/MainActivity;->pendingBytes:[B
    new-instance v0, Landroid/content/Intent;
    const-string v2, "android.intent.action.CREATE_DOCUMENT"
    invoke-direct {v0, v2}, Landroid/content/Intent;-><init>(Ljava/lang/String;)V
    const-string v2, "android.intent.category.OPENABLE"
    invoke-virtual {v0, v2}, Landroid/content/Intent;->addCategory(Ljava/lang/String;)Landroid/content/Intent;
    invoke-virtual {v0, p2}, Landroid/content/Intent;->setType(Ljava/lang/String;)Landroid/content/Intent;
    const-string v2, "android.intent.extra.TITLE"
    invoke-virtual {v0, v2, p1}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;
    iput-object v0, p0, Lcom/renwuzhi/app/Bridge;->pending:Landroid/content/Intent;
    invoke-virtual {v1, p0}, Lcom/renwuzhi/app/MainActivity;->runOnUiThread(Ljava/lang/Runnable;)V
    return-void
.end method

.method public run()V
    .registers 4
    iget-object v0, p0, Lcom/renwuzhi/app/Bridge;->a:Lcom/renwuzhi/app/MainActivity;
    iget-object v1, p0, Lcom/renwuzhi/app/Bridge;->pending:Landroid/content/Intent;
    const/4 v2, 0x2
    invoke-virtual {v0, v1, v2}, Lcom/renwuzhi/app/MainActivity;->startActivityForResult(Landroid/content/Intent;I)V
    return-void
.end method
