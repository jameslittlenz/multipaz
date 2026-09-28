(function() {
    const pagePath = document.location.pathname;
    const basePath = pagePath.substring(0, pagePath.lastIndexOf("/") + 1);
    const loginStyle = document.createElement("style");
    document.head.appendChild(loginStyle);

    // The session itself lives in a server-side, HttpOnly cookie (validatopia_admin_session):
    // this script can't read it, and doesn't need to — the server enforces authorization on
    // every request regardless of what this page thinks. sessionStorage here is only a per-tab
    // UI hint (show the logged-in view, remember the CSRF token to echo back) and is corrected
    // the moment any request comes back 401.
    const csrfKey = "validatopia_admin_csrf_token";
    const loggedInKey = "validatopia_admin_logged_in";

    const isLoggedIn = () => sessionStorage.getItem(loggedInKey) === "1";
    const getCsrfToken = () => sessionStorage.getItem(csrfKey) || "";

    const setLoggedIn = (csrfToken) => {
        sessionStorage.setItem(loggedInKey, "1");
        sessionStorage.setItem(csrfKey, csrfToken);
        loginRefresh();
    };

    const setLoggedOut = () => {
        sessionStorage.removeItem(loggedInKey);
        sessionStorage.removeItem(csrfKey);
        loginRefresh();
    };

    // Every other admin page's script should fetch through this, not raw fetch(): it attaches
    // the CSRF header on state-changing requests and resets the logged-out UI on a 401 (session
    // expired, revoked, or never existed) so a stale sessionStorage flag can't strand the page in
    // a logged-in-looking state that every request then fails.
    window.adminFetch = async function(path, options) {
        options = options || {};
        const method = (options.method || "GET").toUpperCase();
        const headers = Object.assign({}, options.headers || {});
        if (method !== "GET" && method !== "HEAD") {
            headers["X-Admin-Csrf"] = getCsrfToken();
        }
        const response = await fetch(basePath + path, Object.assign({}, options, { headers }));
        if (response.status === 401) {
            setLoggedOut();
        }
        return response;
    };

    const login = document.getElementById("login");

    const loginRefresh = function() {
        let loggedIn = isLoggedIn();
        let hideClass = loggedIn ? "logged_out" : "logged_in";
        loginStyle.textContent = "." + hideClass + " { display: none; }";
        if (login) {
            login.setAttribute("loggedIn", loggedIn);
            if (loggedIn) {
                login.dispatchEvent(new CustomEvent("loggedIn", {}));
            }
        }
    };

    if (login) {
        const loggedInDiv = document.createElement("div");
        loggedInDiv.className = "logged_in";
        login.appendChild(loggedInDiv);
        const logOutButton = document.createElement("button");
        loggedInDiv.appendChild(logOutButton);
        logOutButton.textContent = "Log out";
        logOutButton.addEventListener("click", async function() {
            await window.adminFetch("admin_logout", { method: "POST" });
            setLoggedOut();
        });

        const loggedOutDiv = document.createElement("div");
        loggedOutDiv.className = "logged_out";
        login.appendChild(loggedOutDiv);

        const usernameLabel = document.createElement("label");
        usernameLabel.textContent = "Username:";
        usernameLabel.setAttribute("for", "admin-username");
        const usernameInput = document.createElement("input");
        usernameInput.id = "admin-username";
        usernameInput.type = "text";
        usernameInput.autocomplete = "username";
        usernameLabel.appendChild(usernameInput);

        const passwordLabel = document.createElement("label");
        passwordLabel.textContent = "Password:";
        passwordLabel.setAttribute("for", "admin-password");
        const passwordInput = document.createElement("input");
        passwordInput.id = "admin-password";
        passwordInput.type = "password";
        passwordInput.autocomplete = "current-password";
        passwordLabel.appendChild(passwordInput);

        const logInButton = document.createElement("button");
        logInButton.textContent = "Log in";

        const statusText = document.createElement("span");
        statusText.className = "login_status";
        statusText.setAttribute("role", "status");

        loggedOutDiv.append(usernameLabel, passwordLabel, logInButton, statusText);

        // TOTP step, hidden until the password step asks for it.
        const totpLabel = document.createElement("label");
        totpLabel.textContent = "Authenticator code:";
        totpLabel.setAttribute("for", "admin-totp");
        totpLabel.style.display = "none";
        const totpInput = document.createElement("input");
        totpInput.id = "admin-totp";
        totpInput.type = "text";
        totpInput.inputMode = "numeric";
        totpInput.autocomplete = "one-time-code";
        totpLabel.appendChild(totpInput);
        const totpButton = document.createElement("button");
        totpButton.textContent = "Verify code";
        totpButton.style.display = "none";
        const enrollmentInfo = document.createElement("div");
        enrollmentInfo.className = "totp_enrollment";
        enrollmentInfo.style.display = "none";
        loggedOutDiv.append(totpLabel, totpButton, enrollmentInfo);

        const showTotpStep = (enrollment) => {
            usernameInput.disabled = true;
            passwordInput.disabled = true;
            logInButton.style.display = "none";
            totpLabel.style.display = "inline";
            totpButton.style.display = "inline";
            if (enrollment) {
                enrollmentInfo.style.display = "block";
                enrollmentInfo.innerHTML = "";
                const p1 = document.createElement("p");
                p1.textContent = "No authenticator app is set up for this account yet. " +
                    "Add it manually with this key, then enter the 6-digit code it shows:";
                const p2 = document.createElement("p");
                const code = document.createElement("code");
                code.textContent = enrollment.secret;
                p2.appendChild(code);
                enrollmentInfo.append(p1, p2);
            }
            totpInput.focus();
        };

        const resetLoginForm = () => {
            usernameInput.disabled = false;
            passwordInput.disabled = false;
            passwordInput.value = "";
            totpInput.value = "";
            logInButton.style.display = "inline";
            totpLabel.style.display = "none";
            totpButton.style.display = "none";
            enrollmentInfo.style.display = "none";
        };

        const logIn = async function() {
            statusText.textContent = "";
            const response = await fetch(basePath + "admin_login", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ username: usernameInput.value, password: passwordInput.value })
            });
            const body = await response.json();
            if (body.error) {
                statusText.textContent = "Incorrect username or password.";
                return;
            }
            if (body.status === "locked") {
                statusText.textContent =
                    "Too many failed attempts. Try again in " + body.retry_after_seconds + "s.";
                return;
            }
            if (body.status === "totp_enrollment_required") {
                showTotpStep({ secret: body.secret });
                return;
            }
            if (body.status === "totp_required") {
                showTotpStep(null);
                return;
            }
        };

        const verifyTotp = async function() {
            statusText.textContent = "";
            const response = await fetch(basePath + "admin_login_totp", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ username: usernameInput.value, code: totpInput.value })
            });
            const body = await response.json();
            if (body.error) {
                statusText.textContent = "Incorrect code.";
                totpInput.value = "";
                return;
            }
            if (body.status === "locked") {
                statusText.textContent =
                    "Too many failed attempts. Try again in " + body.retry_after_seconds + "s.";
                return;
            }
            if (body.status === "ok") {
                resetLoginForm();
                setLoggedIn(body.csrf_token);
            }
        };

        logInButton.addEventListener("click", logIn);
        passwordInput.addEventListener("keydown", (e) => { if (e.key === "Enter") logIn(); });
        totpButton.addEventListener("click", verifyTotp);
        totpInput.addEventListener("keydown", (e) => { if (e.key === "Enter") verifyTotp(); });
    }

    loginRefresh();
})()
