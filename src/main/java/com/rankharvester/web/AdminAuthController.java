package com.rankharvester.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 后台登录：POST /api/admin/login {password} → {token}。token 放后续请求头 X-Admin-Token。 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

    private final AdminAuthService auth;

    public AdminAuthController(AdminAuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody(required = false) Map<String, String> body) {
        String otp = body == null ? null : body.get("otp");
        String token = auth.login(otp);
        if (token == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "动态码错误");
        }
        return Map.of("token", token);
    }
}
