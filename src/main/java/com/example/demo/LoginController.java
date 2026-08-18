package com.example.demo;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {
    @GetMapping({"/", "/login", "/home", "/drive", "/login/authenticate"})
    public String authenticate() {
        return "index";
    }
}
