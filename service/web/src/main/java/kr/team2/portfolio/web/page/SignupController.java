package kr.team2.portfolio.web.page;

import jakarta.validation.Valid;
import kr.team2.portfolio.auth.api.AuthFacade;
import kr.team2.portfolio.common.BusinessException;
import kr.team2.portfolio.common.ErrorCode;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/** 회원가입 화면 (01 FR-AUTH-01). 성공하면 로그인 화면으로 보낸다. */
@Controller
class SignupController {

    private final AuthFacade auth;

    SignupController(AuthFacade auth) {
        this.auth = auth;
    }

    @GetMapping("/signup")
    String form(Model model) {
        model.addAttribute("form", new SignupForm());
        return "signup";
    }

    @PostMapping("/signup")
    String submit(@Valid @ModelAttribute("form") SignupForm form, BindingResult binding) {
        if (binding.hasErrors()) {
            return "signup";
        }
        try {
            auth.register(form.getUsername(), form.getPassword());
        } catch (BusinessException e) {
            if (e.code() == ErrorCode.USERNAME_TAKEN) {
                binding.rejectValue("username", e.code().name(), e.code().defaultMessage());
                return "signup";
            }
            throw e;
        }
        return "redirect:/login?signup";
    }
}
