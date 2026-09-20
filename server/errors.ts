export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

export function refusedResponse() {
  return new ApiError(422, 'DESCRIPTION_REFUSED', '暂时无法描述这份内容，请尝试另一张图片或视频。');
}

export function invalidResponse() {
  return new ApiError(502, 'INVALID_RESPONSE', '收到的描述格式不完整，请重试。');
}

export function incompleteResponse() {
  return new ApiError(502, 'INCOMPLETE_RESPONSE', '这次描述未能完整生成，请重试。');
}
