package com.devhub.module.project.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.devhub.common.OperLog;
import com.devhub.common.ProjectScopeAspect;
import com.devhub.module.ai.common.Result;
import com.devhub.module.project.entity.PrjAttachment;
import com.devhub.module.project.mapper.PrjAttachmentMapper;
import com.devhub.module.task.mapper.PrjTaskMapper;
import com.devhub.module.defect.mapper.PrjDefectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

/**
 * 对象附件（P0-3）：任务/缺陷挂附件，本地磁盘存储 + 流式下载。
 * 上传/删除做项目成员校验（对象类型为运行时参数，手工走切面公共方法）。
 */
@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private static final Set<String> VALID_TYPES = Set.of("TASK", "DEFECT");

    private final PrjAttachmentMapper attMapper;
    private final PrjTaskMapper taskMapper;
    private final PrjDefectMapper defectMapper;
    private final ProjectScopeAspect scopeGuard;

    @Value("${devhub.upload.dir:./data/attachments}")
    private String uploadDir;

    @GetMapping
    public Result<List<PrjAttachment>> list(@RequestParam String objType, @RequestParam Long objId) {
        return Result.success(attMapper.selectList(new LambdaQueryWrapper<PrjAttachment>()
                .eq(PrjAttachment::getObjType, objType)
                .eq(PrjAttachment::getObjId, objId)
                .orderByDesc(PrjAttachment::getId)));
    }

    @OperLog(module = "协作", action = "上传附件")
    @PostMapping("/upload")
    public Result<PrjAttachment> upload(@RequestParam String objType, @RequestParam Long objId,
                                        @RequestParam("file") MultipartFile file) throws IOException {
        if (!VALID_TYPES.contains(objType)) return Result.fail(400, "objType 必须为 TASK/DEFECT");
        if (file.isEmpty()) return Result.fail(400, "文件为空");
        Long projectId = projectIdOf(objType, objId);
        if (projectId == null) return Result.fail(404, "对象不存在");
        String deny = scopeGuard.checkMemberWrite(currentUsername(), projectId, false);
        if (deny != null) return Result.fail(403, deny);

        Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        // 存储名加时间戳防重名，保留原始扩展
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String stored = System.currentTimeMillis() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8)
                + (original.contains(".") ? original.substring(original.lastIndexOf('.')) : "");
        Path target = dir.resolve(stored);
        file.transferTo(target);

        PrjAttachment a = new PrjAttachment();
        a.setObjType(objType); a.setObjId(objId);
        a.setFileName(original); a.setFileSize(file.getSize());
        a.setContentType(file.getContentType());
        a.setStoredPath(target.toString());
        a.setUploader(currentUsername());
        attMapper.insert(a);
        return Result.success(a);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) throws IOException {
        PrjAttachment a = attMapper.selectById(id);
        if (a == null) return ResponseEntity.notFound().build();
        Path path = Paths.get(a.getStoredPath());
        if (!Files.exists(path)) return ResponseEntity.notFound().build();
        String encoded = java.net.URLEncoder.encode(a.getFileName(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(path))
                .body(new FileSystemResource(path));
    }

    @OperLog(module = "协作", action = "删除附件")
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) throws IOException {
        PrjAttachment a = attMapper.selectById(id);
        if (a == null) return Result.success("ok");
        Long projectId = projectIdOf(a.getObjType(), a.getObjId());
        if (projectId != null) {
            String deny = scopeGuard.checkMemberWrite(currentUsername(), projectId, false);
            if (deny != null) return Result.fail(403, deny);
        }
        attMapper.deleteById(id);
        Files.deleteIfExists(Paths.get(a.getStoredPath()));
        return Result.success("ok");
    }

    private Long projectIdOf(String objType, Long objId) {
        if ("TASK".equals(objType)) {
            var t = taskMapper.selectById(objId);
            return t == null ? null : t.getProjectId();
        }
        var d = defectMapper.selectById(objId);
        return d == null ? null : d.getProjectId();
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
