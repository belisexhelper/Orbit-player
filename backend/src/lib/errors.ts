export class HttpError extends Error {
  constructor(
    public statusCode: number,
    message: string,
    public code = 'error',
  ) {
    super(message);
  }
}

export const badRequest = (msg: string, code = 'bad_request') => new HttpError(400, msg, code);
export const unauthorized = (msg = 'Niste prijavljeni', code = 'unauthorized') => new HttpError(401, msg, code);
export const forbidden = (msg = 'Nemate dozvolu za ovu radnju', code = 'forbidden') => new HttpError(403, msg, code);
export const notFound = (msg = 'Nije pronađeno', code = 'not_found') => new HttpError(404, msg, code);
export const conflict = (msg: string, code = 'conflict') => new HttpError(409, msg, code);
