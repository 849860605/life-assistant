/** 新密码哪里不合规（和后端 PasswordPolicy 一致：8 到 64 位，同时有字母和数字）；没填或合规时返回空串。 */
export function passwordProblem(pw: string): string {
  if (!pw) return "";
  if (pw.length < 8 || pw.length > 64) return "密码要 8 到 64 位";
  if (!/[A-Za-z]/.test(pw) || !/\d/.test(pw)) return "密码要同时有字母和数字";
  return "";
}

/** 邮箱格式粗查，和后端一样只认常见格式 */
export function looksLikeEmail(email: string): boolean {
  return /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/.test(email.trim());
}
