package com.tale.plugins.qiniu;

import com.blade.ioc.annotation.Bean;
import com.blade.ioc.annotation.Inject;
import com.blade.kit.DateKit;
import com.blade.kit.JsonKit;
import com.blade.kit.StringKit;
import com.blade.mvc.RouteContext;
import com.blade.mvc.hook.WebHook;
import com.blade.mvc.http.Request;
import com.blade.mvc.http.Response;
import com.blade.mvc.multipart.FileItem;
import com.blade.mvc.ui.RestResponse;
import com.google.gson.Gson;
import com.qiniu.common.Zone;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.UploadManager;
import com.qiniu.storage.model.DefaultPutRet;
import com.qiniu.util.Auth;
import com.tale.bootstrap.TaleConst;
import com.tale.model.dto.Types;
import com.tale.model.entity.Attach;
import com.tale.model.entity.Logs;
import com.tale.model.entity.Users;
import com.tale.service.OptionsService;
import com.tale.service.SiteService;
import com.tale.utils.TaleUtils;
import io.github.biezhi.anima.Anima;
import io.github.biezhi.anima.core.dml.Select;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author biezhi
 * @date 2017/12/14
 */
@Bean
public class QiniuWebHook implements WebHook {


    private static final Logger log = LoggerFactory.getLogger(QiniuWebHook.class);

    @Inject
    private OptionsService optionsService;

    @Inject
    private SiteService siteService;

    static Auth auth = null;

    static String bucket = null;

    static String upToken = null;

    static UploadManager uploadManager = null;

    static BucketManager bucketManager = null;

    public void initQiniu(){
        String name = optionsService.getOption(QiniuConst.PLUGIN_KEY_OPERATORNAME);
        String pass = optionsService.getOption(QiniuConst.PLUGIN_KEY_OPERATORPWD);
        bucket = optionsService.getOption(QiniuConst.PLUGIN_KEY_BUCKET_NAME);

        if (StringKit.isNotBlank(name) && StringKit.isNotBlank(pass) && StringKit.isNotBlank(bucket)) {
            QiniuWebHook.auth = Auth.create(name, pass);
            QiniuWebHook.upToken = QiniuWebHook.auth.uploadToken(bucket);
            //构造一个带指定Zone对象的配置类
            Configuration cfg = new Configuration(Zone.autoZone());
            QiniuWebHook.uploadManager = new UploadManager(cfg);
            QiniuWebHook.bucketManager = new BucketManager(QiniuWebHook.auth, cfg);
        }
    }

    @Override
    public boolean before(RouteContext routeContext) {
        boolean isActive = TaleConst.OPTIONS.getBoolean(QiniuConst.PLUGIN_KEY_ACTIVE, false);
        if (!isActive) {
            return true;
        }

        log.info("执行七牛插件");

        Request  request  = routeContext.request();
        Response response = routeContext.response();
        String   uri      = request.uri();

        // 拦截上传接口
        if (QiniuConst.UPLOAD_URI.equals(uri)) {
            Map<String, FileItem> fileItemMap = request.fileItems();
            Collection<FileItem>  fileItems   = fileItemMap.values();
            try {
                List<Attach> attaches = fileItems.parallelStream()
                        .map(this::upload)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                response.json(RestResponse.ok(attaches));
            } catch (Exception e) {
                log.error("七牛上传失败", e);
                response.json(RestResponse.fail());
                return false;
            }
            return false;
        }

        // 删除接口
        if (uri.startsWith(QiniuConst.DELETE_URI)) {
            if(null==bucketManager){
                initQiniu();
            }
            try {
                Users users  = TaleUtils.getLoginUser();
                Integer id     = Integer.valueOf(uri.substring(uri.lastIndexOf("/")+1));
                Attach attach = new Select().from(Attach.class).byId(id);
                if (null == attach) {
                    response.json(RestResponse.fail("不存在该附件"));
                    return false;
                }
                // 删除文件
                bucketManager.delete(bucket, attach.getFkey().substring(1));
                Anima.delete().from(Attach.class).deleteById(id);
                siteService.cleanCache(Types.SYS_STATISTICS);

                new Logs("删除附件", attach.getFkey(), request.address(), users.getUid()).save();
                response.json(RestResponse.ok());
            } catch (Exception e) {
                String msg = "附件删除失败";
                log.error(msg, e);
                response.json(RestResponse.fail(msg));
            }
            return false;
        }
        return true;
    }

    private Attach upload(FileItem fileItem) {

        if(null==uploadManager){
            initQiniu();
        }



        Users users = TaleUtils.getLoginUser();
        Integer uid   = users.getUid();
        String fname = fileItem.getFileName();
        Attach attach = new Attach();
        attach.setFname(fname);
        if (fileItem.getLength() / 1024 <= TaleConst.MAX_FILE_SIZE) {
            String fkey  = TaleUtils.getFileKey(fname);
            String ftype = fileItem.getContentType().contains("image") ? Types.IMAGE : Types.FILE;
            try {
                String filePath = TaleUtils.UP_DIR + fkey;
                Files.write(Paths.get(filePath), fileItem.getData());
                com.qiniu.http.Response result = uploadManager.put(filePath, fkey.substring(1), upToken);
                if (null != result) {
                    //上传到七牛,解析上传成功的结果
                    DefaultPutRet putRet = new Gson().fromJson(result.bodyString(), DefaultPutRet.class);
                    attach.setFname(fname);
                    attach.setFkey(fkey);
                    attach.setFtype(ftype);
                    attach.setAuthorId(uid);
                    attach.setCreated(DateKit.nowUnix());
                    attach.save();
                } else {
                    log.warn("上传文件 [{}] 失败", fname);
                }
                Files.delete(Paths.get(filePath));
            } catch (IOException e) {
                log.error("文件上传失败", e);
            }
        }
        siteService.cleanCache(Types.SYS_STATISTICS);
        return attach;
    }

}
