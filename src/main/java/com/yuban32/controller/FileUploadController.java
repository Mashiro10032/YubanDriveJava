package com.yuban32.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yuban32.entity.FileInfo;
import com.yuban32.entity.Folder;
import com.yuban32.entity.User;
import com.yuban32.entity.UserStorageQuota;
import com.yuban32.mapper.UserStorageQuotaMapper;
import com.yuban32.response.Result;
import com.yuban32.service.ChunkInfoService;
import com.yuban32.service.FileInfoService;
import com.yuban32.service.FolderService;
import com.yuban32.service.UserService;
import com.yuban32.util.JWTUtils;
import com.yuban32.util.LocalDateTimeFormatterUtils;
import com.yuban32.util.StorageUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @author Yuban32
 * @ClassName FileUploadController
 * @Description 文件控制层 不包括文件下载
 * @Date 2023年02月22日
 */
@Slf4j
@RestController
@RequestMapping("/fileUpload")
public class FileUploadController {

    @Autowired
    private JWTUtils jwtUtils;
    @Autowired
    private FileInfoService fileInfoService;
    @Autowired
    private ChunkInfoService chunkInfoService;
    @Autowired
    private UserService userService;
    @Autowired
    private UserStorageQuotaMapper userStorageQuotaMapper;
    @Autowired
    private FolderService folderService;
    @Value("${base-file-path.file-path}")
    private String filePath;
    private static final String ROOT = "root";

    @GetMapping("/check")
    @RequiresAuthentication
    public Result checkFile(@RequestParam("md5") String md5,@RequestParam("folderUUID")String folderUUID,@RequestParam("fileName")String fileName , HttpServletRequest request){
        String userName;
        try{
            userName = jwtUtils.getClaimByToken(request.getHeader("Authorization")).getSubject();
        }catch(Exception e){
            e.printStackTrace();
            return Result.error("用户未登录");
        }
        String root = "root";
        LocalDateTimeFormatterUtils localDateTimeFormatterUtils = new LocalDateTimeFormatterUtils();
        //根据用户名查询到用户ID 然后通过用户ID查询 因为用户名是随时会改变的
//        User getUserInfo = userService.getOne(new QueryWrapper<User>().eq("username", userName));

        QueryWrapper<FileInfo> fileInfoQueryByFileNameAndParentID = new QueryWrapper<FileInfo>()
                .eq("f_name", fileName)
                .eq("f_parent_id", folderUUID)
                .eq("f_uploader",userName);
        log.info("检查MD5中...{}",md5);
        //检查是否有完整的文件存在
        Boolean isUploaded = fileInfoService.selectFileByMd5(md5);
        Map<String,Object> data = new HashMap<>();
        log.info("isUploaded,{}",isUploaded);
        data.put("isUploaded",isUploaded);
        //TODO 继续完成上传和下载功能

        /*
        * 1 传来的是md5和文件夹的uuid
        * 2 首先先去数据库查文件md5 如果存在多个的话就是true
        * 3 接着通过文件md5和文件夹uuid来查询 判断当前目录下是否存在同名文件夹 不存在则返回null
        * 4 判断文件夹uuid是否是root 是root的话就通过文件名查询数据库任意一条文件数据后重新把文件夹uuid和相对路径都设置成root
        *       否则就通过第四条查询到的文件数据的相对路径和文件夹uuid是否相同(此条就是为了判断root文件夹的)
        *           不相同则通过文件夹uuid查询文件夹的路径后赋值给第四条查询到的文件数据后再写入到数据库当作秒传
        *              这样做的目的是 同样的文件只存一遍在物理磁盘上 多个文件夹存相同的文件只是在数据库上进行相对路径的映射罢了
        * */
        if(isUploaded){
            List<FileInfo> files = fileInfoService.list(fileInfoQueryByFileNameAndParentID);
            log.info("{}",files.size());
            if (files.isEmpty()){
                List<FileInfo> oneFile = fileInfoService.list(new QueryWrapper<FileInfo>().eq("f_md5", md5));
                log.info("{}",oneFile);
                if (folderUUID.equals(root)){
                    List<FileInfo> list = fileInfoService.list(fileInfoQueryByFileNameAndParentID);
                    if (list.isEmpty()){
                        oneFile.get(oneFile.size()-1).setFileUploader(userName);
                        oneFile.get(oneFile.size()-1).setFileRelativePath(root);
                        oneFile.get(oneFile.size()-1).setFileParentId(root);
                        oneFile.get(oneFile.size()-1).setFileUploadTime(localDateTimeFormatterUtils.getStartDateTime());
                        fileInfoService.addFile(oneFile.get(oneFile.size()-1));
                        log.info("文件已存在服务器上,但不存在虚拟路径上");
                        return new Result(201,"文件已秒传",data);
                    }else{
                        return new Result(500,"本目录下已有同名文件",null);
                    }
                }else if (oneFile.get(oneFile.size()-1).getFileRelativePath() != folderUUID) {
                    log.info("文件已存在服务器上,但不存在虚拟路径上");
                    Folder folder = folderService.selectFolderByFolderUUID(folderUUID);
                    oneFile.get(oneFile.size()-1).setFileUploader(userName);
                    oneFile.get(oneFile.size()-1).setFileRelativePath(folder.getFolderRelativePath());
                    oneFile.get(oneFile.size()-1).setFileParentId(folderUUID);
                    fileInfoService.addFile(oneFile.get(oneFile.size()-1));
                    return new Result(201,"文件已秒传",data);
                }
            }else {
                return new Result(500,"本目录下已有同名文件",null);
            }
            return new Result(201,"文件已秒传",data);
        }else{
            //没有的话就找分片的信息,并且返回给前端
            List<Integer> chunkList = chunkInfoService.selectChunkListByMd5(md5);
            data.put("chunkList",chunkList);
            return new Result(201,"",data);
        }
    }
    //使用Body传参的话 MultipartFile会序列化失败
    @PostMapping("/chunk")
    @RequiresAuthentication
    public Result uploadChunk(
            @RequestParam("chunk") MultipartFile chunk,
            @RequestParam("md5") String md5,
            @RequestParam("index") Integer index,
            @RequestParam("chunkTotal") Integer chunkTotal,
            @RequestParam("fileSize")Long fileSize,
            @RequestParam("chunkSize")Long chunkSize,
            @RequestParam("fileName")String fileName,
            @RequestParam("fileParentID")String fileParentID,
            @RequestParam("folderUUID")String folderUUID,
            HttpServletRequest request

    ) throws IOException {
        String userName;
        try{
             userName= jwtUtils.getClaimByToken(request.getHeader("Authorization")).getSubject();
        }catch(Exception e){
            e.printStackTrace();
            return Result.error("用户未登录");
        }
        log.info("检查存储容量中...");
        //1. 查询系统可用空间 使用Spring提供的断言来判断上传文件是否大于当前系统存在的磁盘可用空间
        Assert.isTrue(fileSize > new StorageUtils().getDriveFreeSpace(),"系统磁盘可用空间不足");
        //2. 查询用户可用空间
        log.info("存储容量检查通过 剩余大小=>{}",fileSize > new StorageUtils().getDriveFreeSpace());
        UserStorageQuota userStorageQuota = userStorageQuotaMapper.selectUserStorageQuotaByUserName(userName);
        Assert.isTrue(fileSize < (userStorageQuota.getTotalStorage() - userStorageQuota.getUsedStorage()),"用户可用空间不足");
        log.info("用户可用空间检查通过 剩余大小=>{}",userStorageQuota.getTotalStorage() - userStorageQuota.getUsedStorage());


        //根据.来截取字符串数组
        String[] split = fileName.split("\\.");
        //再获取文件类型
        String type = split[split.length - 1];
        String resultFileName = filePath + File.separator  + md5 + "." + type;
        chunkInfoService.saveChunk(chunk,md5,index,chunkSize,resultFileName,userName);
        log.info("上传分片: {},{},{},{}",index,chunkTotal,fileName,userName);

        if(Objects.equals(index,chunkTotal)){
            FileInfo fileInfo = new FileInfo();
            //判断前端传来的folderUUID是否是root
            if(!folderUUID.equals(ROOT)){
                //不是就找父文件夹 然后把文件的相对路径改为父文件夹的相对路径
                Folder folder = folderService.selectFolderByFolderUUID(folderUUID);
                fileInfo.setFileRelativePath(folder.getFolderRelativePath());
            }else {
                //是root就存根目录
                fileInfo.setFileRelativePath(ROOT);
            }
            LocalDateTimeFormatterUtils localDateTimeFormatterUtils = new LocalDateTimeFormatterUtils();
            File file = new File(resultFileName);
            if (file.canRead()){
                //用Tika库来鉴别文件类型
                Tika tika = new Tika();
                String detect = tika.detect(file);
                fileInfo.setFileType(detect);
            }
            //封装对象
            fileInfo.setFileName(fileName);
            fileInfo.setFileMD5(md5);
            fileInfo.setFileSize(fileSize);
            fileInfo.setFileExtension(type);
            fileInfo.setFileParentId(ROOT);
            fileInfo.setFileAbsolutePath(filePath);
            fileInfo.setFileUploader(userName);
            fileInfo.setFileUploadTime(localDateTimeFormatterUtils.getStartDateTime());
            //调用Service接口 把文件数据写入数据库
            fileInfoService.addFile(fileInfo);
            //从数据库中删除掉已添加的分片信息
            chunkInfoService.deleteChunkByMd5(md5);
            //更新已用的存储空间
            userStorageQuota.setUsedStorage(fileSize);
            userStorageQuotaMapper.updateById(userStorageQuota);
            //调用接口 这接口的作用是 是图片的话就创建一个图片缩略图
            fileInfoService.createThumbnail(resultFileName,userName,md5);
            return new Result(200,"文件上传成功",index);
        }else {
            return new Result(201,"分片上传成功",index);
        }
    }
}
