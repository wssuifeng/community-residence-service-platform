/** 与账号DTO一致：中文等Unicode文字、数字、下划线，1～50字符。 */
export const USERNAME_HINT = '1～50 位中文、字母、数字或下划线'
export function isValidUsername(value: string): boolean {
  return value.length >= 1 && value.length <= 50 && /^[\p{L}\p{N}_]+$/u.test(value)
}
